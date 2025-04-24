/*
 * Copyright (c) 2009-2025, NWO-I Centrum Wiskunde & Informatica (CWI)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.cli.ShutdownHookUtils;
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
	private List<File> parallelPreChecks;

	@Parameter(required=false, defaultValue="false")
	private boolean logPathConfig;

	@Parameter(required=false, defaultValue="false")
	private boolean logImports;

	@Parameter(required=false, defaultValue="false")
	private boolean logWrittenFiles;

	@Parameter(required=false, defaultValue="true")
	private boolean warnUnused;

	@Parameter(required=false, defaultValue="true")
	private boolean warnUnusedFormals;

	@Parameter(required=false, defaultValue="true")
	private boolean warnUnusedVariables;

	@Parameter(required=false, defaultValue="true")
	private boolean warnUnusedPatternFormals;

	@Parameter(property="errorsAsWarnings", required=false, defaultValue="false")
	private boolean errorsAsWarnings;

	@Parameter(property="warningsAsErrors", required=false, defaultValue="false")
	private boolean warningsAsErrors;

	/**
	 * Cache for the number of estimated useful parallel jobs for the checker/compiler
	 */
	private long processorEstimate = 0;

	public CompileRascalMojo() {
		super("org.rascalmpl.shell.RascalCompile", "compile");
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

			List<File> todoList = getTodoList(bin, srcs, srcIgnores, "rsc", "tpl", "rascal");
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

			int result = runChecker(verbose, todoList, parallelPreChecks, srcs, srcIgnores, libs, bin, generatedSources);

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
		if (processorEstimate == 0) {
			// check available CPUs, allowing for hyperthreading by asking for the logical count.
			// we just need to know how many things we could run in parallel without pre-empting
			// each other.
			long result = systemInformation.getHardware().getProcessor().getLogicalProcessorCount();
			if (result < 2) {
				return 1;
			}

			getLog().info("Logical processor count: " + result);

			// check total available memory. any memory in use can be swapped out, so
			// we don't really care about the currently _available_ memory.
			long maxMemory = systemInformation.getHardware().getMemory().getTotal();

			getLog().info("Available memory: " + maxMemory / 1000 + " kilobytes");

			// kB means kilobytes means 1000 bytes, while kiB means 1024 butes
			long max2GmemoryDivisions = (maxMemory / 1000) / (2 * 1000 * 1000);

			getLog().info("Number of 2G processors for this amount of memory:" + max2GmemoryDivisions);

			// we use as many processors as we can, without running out of memory
			result = Math.min(result, max2GmemoryDivisions);
			getLog().info("Estimated max number of processors: " + result);
			getLog().info("Max number of processors requested: " + parallelMax);

			if (result < 2) {
				// in case we can't allocated 2G even for one
				return 1;
			}

			processorEstimate = result < 2 ? 1 : Math.min(parallelMax, result);
			getLog().info("Final estimate number of processores: " + processorEstimate);
		}

		return (int) processorEstimate;
	}

	private int runChecker(boolean verbose, List<File> todoList, List<File> prechecks, List<File> srcLocs, List<File> srcIgnores, List<File> libLocs, File binLoc, File generatedSourcesLoc)
			throws IOException, URISyntaxException, Exception {
	    if (!parallel || todoList.size() <= 10 || estimateBestNumberOfParallelProcesses() <= 1) {
	    	return runCheckerSingleThreaded(verbose, todoList, srcLocs, srcIgnores, libLocs, binLoc, generatedSourcesLoc);
		}
		else {
			return runCheckerMultithreaded(verbose, todoList, prechecks, srcLocs, srcIgnores, libLocs, binLoc, generatedSourcesLoc);
		}
	}

	private int runCheckerMultithreaded(boolean verbose, List<File> todoList, List<File> prechecks, List<File> srcs, List<File> srcIgnores, List<File> libs, File bin, File generatedSourcesLoc) throws Exception {
		List<List<File>> chunks = splitTodoList(todoList);
		chunks.add(0, prechecks);
		List<File> tmpBins = chunks.stream().map(handleExceptions(l -> Files.createTempDirectory("rascal-checker").toFile())).collect(Collectors.toList());
		List<File> tmpGeneratedSources = chunks.stream().map(handleExceptions(l -> Files.createTempDirectory("rascal-sources").toFile())).collect(Collectors.toList());
		int result = 0;

		Map<String,String> extraParameters = new HashMap<>();

		try {
			List<Process> processes = new LinkedList<>();
			ShutdownHookUtils.addShutDownHook(new Thread(() -> {
				for (var p: processes) {
					try {
						p.destroy();
					} catch (Exception e) {
					}
				}
			}));
			// reuse previous tpls
			libs.add(bin);

			var todoChunk = chunks.get(0);
			if (!todoChunk.isEmpty()) {
				setExtraCompilerParameters(verbose, todoChunk, extraParameters);
				getLog().info("Pre-compiling common modules " + prechecks.stream().map(f -> f.getName()).collect(Collectors.joining(", ")));
				Process prechecker = runMain(verbose, "", srcs, srcIgnores, libs, tmpGeneratedSources.get(0), tmpBins.get(0), extraParameters, true, 1);
				processes.add(prechecker);

				var exitCode = prechecker.waitFor();
				getLog().info("Pre-compilation finished (" + exitCode + ")");
				result += exitCode; // block until the process is finished

				if (exitCode == 137) {
					getLog().error("JVM " + 0 + "was killed by the OS; possibly for taking to much memory");
				}

				// add the result of this pre-build to the libs of the parallel processors to reuse .tpl files
				libs.add(tmpBins.get(0));
			}

			// starts the processes asynchronously
			for (int i = 1; i < chunks.size(); i++) {
				var chunk = chunks.get(i);
				if (!chunk.isEmpty()) { // can become empty by removing the pre-checks.
					setExtraCompilerParameters(verbose, chunks.get(i), extraParameters);
					getLog().info("Compiler " + i + " started on a parallel job of " + chunks.get(i).size() + " modules.");
					processes.add(runMain(verbose, "", srcs, srcIgnores, libs, tmpGeneratedSources.get(i), tmpBins.get(i), extraParameters, i == 1, chunks.size()));
				}
			}


			List<List<String>> otherOutput = new ArrayList<>();
			for (int i = 2; i < processes.size(); i++) {
				Process p = processes.get(i);
				var ourQueue = new ArrayList<String>();
				otherOutput.add(ourQueue);
				CompletableFuture.runAsync(() -> {
					try (var reader = new InputStreamReader(p.getInputStream())) {
						var buffer = new char[8 * 1024];
						int filled = 0;
						while (true) {
							int read = reader.read(buffer, filled, buffer.length - filled);
							if (read == -1) {
								break;
							}
							filled += read;
							if (filled == buffer.length) {
								ourQueue.add(new String(buffer));
							}
							filled = 0;
						}
						if (filled > 0) {
							ourQueue.add(new String(buffer, 0, filled));
						}
					}
					catch (Exception e) {
						return;
					}
				});
			}


			// wait until _all_ processes have exited and print their output in big chunks in order of process creation
			for (int i = 1; i < processes.size(); i++) {
				int exitCode = processes.get(i).waitFor();

				if (i >= 2) {
					// copy the output from the queue
					otherOutput.get(i - 2).forEach(System.out::print);
				}

				if (exitCode == 137) {
					getLog().error("JVM " + i + "was killed by the OS; possibly for taking too much memory");
				}

				result += exitCode;
				getLog().info("Compiler " + i + " finished ("+ exitCode + ") on a job of " + chunks.get(i).size() + " modules.");
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

	private int runCheckerSingleThreaded(boolean verbose, List<File> todoList, List<File> srcLocs, List<File> srcIgnores, List<File> libLocs, File binLoc, File generated) throws URISyntaxException, IOException, MojoExecutionException {
		getLog().info("Running single checker process");
		try {
			setExtraCompilerParameters(verbose, todoList, extraParameters);
			return runMain(verbose, "",srcLocs, srcIgnores, libLocs, generated, binLoc, extraParameters, true, 1).waitFor();
		} catch (InterruptedException e) {
			getLog().error("Checker was interrupted");
			throw new MojoExecutionException(e);
		} catch (IOException e) {
			throw new MojoExecutionException(e);
		}
	}

	/**
	 * These are the compiler-specific parameters of the Compile::main function.
	 * The todoList is passed as the -modules parameter. That's the most important
	 * one. The others are boolean configuration parameters which aid in debugging.
	 */
	private void setExtraCompilerParameters(boolean verbose, List<File> todoList, Map<String, String> extraParameters) {
		extraParameters.put("modules", files(todoList));
		extraParameters.put("logPathConfig", Boolean.toString(logPathConfig));
		extraParameters.put("logImports", Boolean.toString(logImports));
		extraParameters.put("verbose", Boolean.toString(verbose));
		extraParameters.put("logWrittenFiles", Boolean.toString(logWrittenFiles));
		extraParameters.put("warnUnused", Boolean.toString(warnUnused));
		extraParameters.put("warnUnusedVariables", Boolean.toString(warnUnusedVariables));
		extraParameters.put("warnUnusedFormals", Boolean.toString(warnUnusedFormals));
		extraParameters.put("warnUnusedPatternFormals", Boolean.toString(warnUnusedPatternFormals));
		extraParameters.put("warningsAsErrors", Boolean.toString(warningsAsErrors));
		extraParameters.put("errorsAsWarnings", Boolean.toString(errorsAsWarnings));
	}

	private void mergeOutputFolders(File bin, List<File> binFolders) throws IOException {
		for (File tmp : binFolders) {
			getLog().debug("Copying files from " + tmp + " to " + bin);
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
		int procs = estimateBestNumberOfParallelProcesses();
		int chunkSize = todoList.size() / procs;
		int remainder = todoList.size() % procs;
		List<List<File>> result = new ArrayList<>((todoList.size() / chunkSize) + 1);

		// Divide the work evenly. The remainder elements are distributed
		// one-by-one over the prefix of the result list.
		for (int from = 0; from < todoList.size(); from += chunkSize + ((remainder-- > 0 ? 1 : 0))) {
			int to = from + chunkSize + ((remainder > 0) ? 1 : 0);
			result.add(Collections.unmodifiableList(todoList.subList(from, to)));
		}

		return result;
	}
}
