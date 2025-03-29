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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;

import oshi.SystemInfo;

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
	private List<File> parallelPreChecks;

	public CompileRascalMojo() {
		super("org.rascalmpl.shell.RascalCompile", "compile", true, "rsc", "tpl");
	}

	public void execute() throws MojoExecutionException {
		try {
			if (System.getProperty("rascal.compile.skip") != null) {
				getLog().info("Skipping Rascal compiler completely");
				return;
			}

			getLog().info("configuring paths");
			for (File src : srcs) {
				getLog().info("\tregistered source location: " + src);
			}

			for (File ignore : srcIgnores) {
				getLog().warn("\tignoring sources in: " + ignore);
			}

			getLog().info("Checking if any files need compilation...");

			List<File> todoList = getTodoList(bin, srcs, srcIgnores);
			todoList.removeAll(parallelPreChecks);

			if (!todoList.isEmpty()) {
				getLog().info("Stale source files have been found:");
				for (File todo : todoList) {
					getLog().info("\t" + todo);
				}
			}
			else {
				getLog().info("No stale source files have been found, skipping compilation.");
				return;
			}

			// complete libraries with maven artifacts which include a META-INF/RASCAL.MF file
			libs.addAll(collectDependentArtifactLibraries(project));

			for (File lib : libs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Files have been configured.");

			int result = runChecker(verbose, todoList, parallelPreChecks, srcs, libs, bin, generatedSources);

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

	private int estimateBestNumberOfParallelProcesses() {
		// check available CPUs, allowing for hyperthreading by asking for the logical count.
		// we just need to know how many things we could run in parallel without pre-empting
		// each other.
		long result = systemInformation.getHardware().getProcessor().getLogicalProcessorCount();
		if (result < 2) {
			return 1;
		}

		// check total available memory. any memory in use can be swapped out, so
		// we don't really care about the currently _available_ memory.
		long maxMemory = systemInformation.getHardware().getMemory().getTotal();
		// kB means kilobytes means 1000 bytes, while kiB means 1024 butes
		long max2GmemoryDivisions = maxMemory / (2 * 1000 * 1000);

		// we use as many processors as we can, without running out of memory
		result = Math.min(result, max2GmemoryDivisions);

		if (result < 2) {
			// in case we can't allocated 2G even for one
			return 1;
		}

		return (int) Math.min(parallelMax, result);
	}

	private int runChecker(boolean verbose, List<File> todoList, List<File> prechecks, List<File> srcLocs, List<File> libLocs, File binLoc, File generatedSourcesLoc)
			throws IOException, URISyntaxException, Exception {
	    if (!parallel || todoList.size() <= 10 || estimateBestNumberOfParallelProcesses() <= 1) {
	    	return runCheckerSingleThreaded(verbose, todoList, srcLocs, libLocs, binLoc, generatedSourcesLoc);
		}
		else {
			return runCheckerMultithreaded(verbose, todoList, prechecks, srcLocs, libLocs, binLoc, generatedSourcesLoc);
		}
	}

	private int runCheckerMultithreaded(boolean verbose, List<File> todoList, List<File> prechecks, List<File> srcs, List<File> libs, File bin, File generatedSourcesLoc) throws Exception {
		todoList.removeAll(prechecks);
		List<List<File>> chunks = splitTodoList(todoList);
		chunks.add(0, prechecks);
		List<File> tmpBins = chunks.stream().map(handleExceptions(l -> Files.createTempDirectory("rascal-checker").toFile())).collect(Collectors.toList());
		List<File> tmpGeneratedSources = chunks.stream().map(handleExceptions(l -> Files.createTempDirectory("rascal-sources").toFile())).collect(Collectors.toList());
		int result = 0;

		Map<String,String> extraParameters = Map.of("modules", todoList.stream().map(Object::toString).collect(Collectors.joining(File.pathSeparator)));

		try {
			List<Process> processes = new LinkedList<>();
			Process prechecker = runMain(verbose, chunks.get(0), srcs, libs, tmpGeneratedSources.get(0), tmpBins.get(0), extraParameters, true);

			result += prechecker.waitFor(); // block until the process is finished

			// add the result of this pre-build to the libs of the parallel processors to reuse .tpl files
			libs.add(tmpBins.get(0));

			// starts the processes asynchronously
			for (int i = 1; i < chunks.size(); i++) {
				processes.add(runMain(verbose, chunks.get(i), srcs, libs, tmpGeneratedSources.get(i), tmpBins.get(i), extraParameters, i <= 1));
			}

			// wait until _all_ processes have exited and print their output in big chunks in order of process creation
			for (int i = 0; i < processes.size(); i++) {
				if (i <= 1) {
					// the first process has inherited our IO
					result += processes.get(i).waitFor();
				} else {
					// the other IO we read in asynchronously
					result += readStandardOutputAndWait(processes.get(i));
				}
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
	}

	private int readStandardOutputAndWait(Process p) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
    		String line;
    		while ((line = reader.readLine()) != null) {
      			System.out.println(line);
    		}

			return p.waitFor();
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private int runCheckerSingleThreaded(boolean verbose, List<File> todoList, List<File> srcLocs, List<File> libLocs, File binLoc, File generated) throws URISyntaxException, IOException, MojoExecutionException {
		getLog().info("Running single checker process");
		try {
			return runMain(verbose, todoList, srcLocs, libLocs, generated, binLoc, extraParameters, true).waitFor();
		} catch (InterruptedException e) {
			getLog().error("Checker was interrupted");
			throw new MojoExecutionException(e);
		} catch (IOException e) {
			throw new MojoExecutionException(e);
		}
	}

	private void mergeOutputFolders(File bin, List<File> binFolders) throws IOException {
		for (File tmp : binFolders) {
			getLog().info("Copying files from " + tmp + " to " + bin);
			mergeOutputFolders(bin, tmp);
		}
	}

	private static void mergeOutputFolders(File dst, File src) throws IOException {
		Path dstPath = dst.toPath();
		Path srcPath = src.toPath();

		Files.walkFileTree(srcPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dstPath.resolve(srcPath.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.move(file, dstPath.resolve(srcPath.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

	/**
	 * Divide number of modules evenly over available cores.
	 * TodoList is sorted to keep modules close that are in the same folder.
	 */
	private List<List<File>> splitTodoList(List<File> todoList) {
		todoList.sort(File::compareTo); // improves cohesion of a chunk
		int chunkSize = todoList.size() / estimateBestNumberOfParallelProcesses();
		List<List<File>> result = new ArrayList<>();

		for (int from = 0; from <= todoList.size(); from += chunkSize) {
			result.add(Collections.unmodifiableList(todoList.subList(from, Math.min(from + chunkSize, todoList.size()))));
		}

		return result;
	}
}
