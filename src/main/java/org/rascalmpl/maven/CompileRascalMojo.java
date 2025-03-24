/*******************************************************************************
 * Copyright (c) 2019 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 *   * Davy Landman - davy.landman@swat.engineering - Swat.engineering
 */
package org.rascalmpl.maven;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;

/**
 * Maven Goal for Rascal compilation. The input is a list of
 * Rascal source folders, the output is for each module in the source tree:
 * a .tpl file and possibly/optionally a .class file. Also a list of errors
 * and warnings is printed on stderr.
 *
 * This mojo starts a process to run the main function of the compiler written in Rascal.
 * When the todo lists is long and there are cores availaable, multiple processes
 * are started to divide the work evenly.
 */
@Mojo(name="compile", inheritByDefault=false, defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CompileRascalMojo extends AbstractRascalMojo
{
	@Parameter(property="parallel", required = false, defaultValue="false")
	private boolean parallel;

	@Parameter(property="parallelMax", required = false, defaultValue="4")
	private int parallelMax;

	@Parameter(property = "parallelPreChecks", required = false )
	private List<String> parallelPreChecks;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
  	private MavenSession session;

	public CompileRascalMojo() {
		super("org.rascalmpl.shell.RascalCompile", "compile");
	}

	public void execute() throws MojoExecutionException {
		try {
			Path binLoc = Path.of(bin);

			var generatedSourcesLoc = Path.of(generatedSources);
			List<Path> srcLocs = locations(srcs);
			List<Path> ignoredLocs = locations(srcIgnores);
			List<Path> libLocs = locations(libs);
			List<Path> prechecks = locations(parallelPreChecks);

			if (System.getProperty("rascal.compile.skip") != null) {
				getLog().info("Skipping Rascal compiler completely");
				return;
			}

			getLog().info("configuring paths");
			for (Path src : srcLocs) {
				getLog().info("\tregistered source location: " + src);
			}

			for (Path ignore : ignoredLocs) {
				getLog().warn("\tignoring sources in: " + ignore);
			}

			getLog().info("Checking if any files need compilation...");

			List<Path> todoList = getTodoList(binLoc, srcLocs, ignoredLocs);
			todoList.removeAll(prechecks);

			if (!todoList.isEmpty()) {
				getLog().info("Stale source files have been found:");
				for (Path todo : todoList) {
					getLog().info("\t" + todo);
				}
			}
			else {
				getLog().info("No stale source files have been found, skipping compilation.");
				return;
			}

			// complete libraries with maven artifacts which include a META-INF/RASCAL.MF file
			libLocs.addAll(collectDependentArtifactLibraries(project));

			for (Path lib : libLocs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Paths have been configured.");

			int result = runChecker(verbose, todoList, prechecks, srcLocs, libLocs, binLoc, generatedSourcesLoc);

			if (result > 0) {
				throw new MojoExecutionException("Errors found while checking.");
			}

			return;
		}
		catch (IOException e) {
			throw new MojoExecutionException(e);
		}
		catch (InclusionScanException e) {
			throw new MojoExecutionException(e);
		}
		catch (Throwable e) {
			throw new MojoExecutionException(e);
		}
	}

	private int parallelAmount() {
	    // check available CPUs
		long result = Runtime.getRuntime().availableProcessors();
		if (result < 2) {
			return 1;
		}
		// check available memory
		result = Math.min(result, Runtime.getRuntime().maxMemory() / (2 * 1024 * 1024));
		if (result < 2) {
			return 1;
		}
		return (int) Math.min(parallelMax, result);
	}

	private int runChecker(boolean verbose, List<Path> todoList, List<Path> prechecks, List<Path> srcLocs, List<Path> libLocs, Path binLoc, Path generatedSourcesLoc)
			throws IOException, URISyntaxException, Exception {
	    if (!parallel || todoList.size() <= 10 || parallelAmount() <= 1) {
	    	return runCheckerSingleThreaded(verbose, todoList, srcLocs, libLocs, binLoc, generatedSourcesLoc);
		}
		else {
			return runCheckerMultithreaded(verbose, todoList, prechecks, srcLocs, libLocs, binLoc, generatedSourcesLoc);
		}
	}

	private int runCheckerMultithreaded(boolean verbose, List<Path> todoList, List<Path> prechecks, List<Path> srcs, List<Path> libs, Path bin, Path generatedSourcesLoc) throws Exception {
		todoList.removeAll(prechecks);
		List<List<Path>> chunks = splitTodoList(todoList);
		chunks.add(0, prechecks);
		List<Path> tmpBins = chunks.stream().map(handleExceptions(l -> Files.createTempDirectory("rascal-checker"))).collect(Collectors.toList());
		List<Path> tmpGeneratedSources = chunks.stream().map(handleExceptions(l -> Files.createTempDirectory("rascal-sources"))).collect(Collectors.toList());
		int result = 0;

		try {
			List<Process> processes = new LinkedList<>();
			Process prechecker = runMain(verbose, chunks.get(0), srcs, libs, tmpGeneratedSources.get(0), tmpBins.get(0));
			result += prechecker.waitFor(); // block until the process is finished

			// starts the processes asynchronously
			for (int i = 1; i < chunks.size(); i++) {
				processes.add(runMain(verbose, chunks.get(1), srcs, libs, tmpGeneratedSources.get(i), tmpBins.get(i)));
			}

			// wait until _all_ processes have exited.
			for (Process p : processes) {
				result += p.waitFor();
			}

			// merge the output tpl folders, no matter how many errors have been detected
			mergeOutputFolders(bin, tmpBins);

			// we also merge the generated sources (not used at the moment)
			mergeOutputFolders(generatedSourcesLoc, tmpGeneratedSources);

			if (result > 0) {
				throw new MojoExecutionException("Checker found errors");
			}

			return result;
		}
		catch (IOException e) {
			throw new MojoExecutionException("Unable to prepare temporary directories for the checker.");
		}
		catch (InterruptedException e) {
		    throw new MojoExecutionException("Checker was interrupted");
		}
		finally {
			// delete all the temporaries to be sure
			Stream.concat(tmpBins.stream(), tmpGeneratedSources.stream())
				.forEach(pathToBeDeleted -> {
					try (Stream<Path> paths = Files.walk(pathToBeDeleted)) {
						paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
					}
					catch (IOException e) {
						getLog().warn(e);
					}
				}
			);
		}
	}

	private int runCheckerSingleThreaded(boolean verbose, List<Path> todoList, List<Path> srcLocs, List<Path> libLocs, Path binLoc, Path generated) throws URISyntaxException, IOException, MojoExecutionException {
		getLog().info("Running single checker process");
		try {
			return runMain(verbose, todoList, srcLocs, libLocs, generated, binLoc).waitFor();
		} catch (InterruptedException e) {
			getLog().error("Checker was interrupted");
			throw new MojoExecutionException(e);
		} catch (IOException e) {
			throw new MojoExecutionException(e);
		}
	}

	private void mergeOutputFolders(Path bin, List<Path> binFolders) throws IOException {
		for (Path tmp : binFolders) {
			getLog().info("Copying files from " + tmp + " to " + bin);
			mergeOutputFolders(bin, tmp);
		}
	}

	private static void mergeOutputFolders(Path dst, Path src) throws IOException {
		Files.walkFileTree(src, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

	/**
	 * Divide number of modules evenly over available cores.
	 * TodoList is sorted to keep modules close that are in the same folder.
	 */
	private List<List<Path>> splitTodoList(List<Path> todoList) {
		todoList.sort(Path::compareTo); // improves cohesion of a chunk
		int chunkSize = todoList.size() / parallelAmount();
		List<List<Path>> result = new ArrayList<>();

		for (int from = 0; from <= todoList.size(); from += chunkSize) {
			result.add(Collections.unmodifiableList(todoList.subList(from, Math.min(from + chunkSize, todoList.size()))));
		}

		return result;
	}
}
