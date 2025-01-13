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
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.rascalmpl.debug.IRascalMonitor;
import org.rascalmpl.exceptions.Throw;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.IEvaluator;
import org.rascalmpl.interpreter.result.Result;
import org.rascalmpl.library.util.PathConfig;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.util.ConcurrentSoftReferenceObjectPool;
import org.rascalmpl.values.ValueFactoryFactory;

import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.IList;
import io.usethesource.vallang.IListWriter;
import io.usethesource.vallang.ISet;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.IValue;
import io.usethesource.vallang.IValueFactory;
import io.usethesource.vallang.exceptions.FactTypeUseException;

/**
 * Maven Goal for Rascal compilation. The input is a list of
 * Rascal source folders, the output is for each module in the source tree:
 * a .tpl file and possibly/optionally a .class file. Also a list of errors
 * and warnings is printed on stderr.
 *
 * TODO The Mojo currently uses the Rascal interpreter to run
 * what is currently finished of the compiler and type-checker. Since the compiler
 * is not finished yet, the behavior of this mojo is highly experimental and fluid.
 *
 * TODO When the compiler will be bootstrapped, this mojo will run a generated
 * compiler instead of the source code of the compiler inside the Rascal interpreter.
 *
 */
@Mojo(name="compile", inheritByDefault=false, defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CompileRascalMojo extends AbstractMojo
{
	private static final String UNEXPECTED_ERROR = "unexpected error during Rascal compiler run";
	private static final String MAIN_COMPILER_MODULE = "lang::rascalcore::check::Checker";
	private static final String COMPILER_CONFIG_MODULE = "lang::rascalcore::check::RascalConfig";

	private static final ISourceLocation[] MAIN_COMPILER_SEARCH_PATH = new ISourceLocation[] {
		URIUtil.correctLocation("lib", "typepal", ""),
		URIUtil.correctLocation("lib", "rascal-core", "")
	};
	private static final URIResolverRegistry reg = URIResolverRegistry.getInstance();
	private static final IValueFactory VF = ValueFactoryFactory.getValueFactory();
	

	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
	private String bin;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "resources", required = true)
	private String resources;

	// generatedSources
	@Parameter(defaultValue = "${project.basedir}/generated-sources", property = "generatedSources", required = true)
	private String generatedSources;

	@Parameter(property = "srcs", required = true )
	private List<String> srcs;

	@Parameter(property = "srcIgnores", required = false )
	private List<String> srcIgnores;

	@Parameter(property = "libs", required = false )
	private List<String> libs;

	@Parameter(defaultValue="false", property= "verbose", required=true)
	private boolean verbose;

	@Parameter(property = "errorsAsWarnings", required = false, defaultValue = "false" )
	private boolean errorsAsWarnings;

	@Parameter(property = "warningsAsErrors", required = false, defaultValue = "false" )
	private boolean warningsAsErrors;

	@Parameter(property="enableStandardLibrary", required = false, defaultValue="true")
	private boolean enableStandardLibrary;

	@Parameter(property="parallel", required = false, defaultValue="false")
	private boolean parallel;

	@Parameter(property="parallelMax", required = false, defaultValue="4")
	private int parallelMax;

	@Parameter(property = "parallelPreChecks", required = false )
	private List<String> parallelPreChecks;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
  	private MavenSession session;

	private Evaluator makeEvaluator(IRascalMonitor monitor, PrintWriter err, PrintWriter out, MavenSession session) throws URISyntaxException, FactTypeUseException, IOException {
		return MojoUtils.makeEvaluator(getLog(), session, monitor, err, out, MAIN_COMPILER_SEARCH_PATH, MAIN_COMPILER_MODULE, COMPILER_CONFIG_MODULE);
	}

	public void execute() throws MojoExecutionException {
		try {
			ISourceLocation binLoc = MojoUtils.location(bin);
			ISourceLocation resourcesLoc = MojoUtils.location(resources);

			if (!binLoc.equals(resourcesLoc)) {
				getLog().info("bin      : " + binLoc);
				getLog().info("resources: " + resourcesLoc);
				getLog().error(new IllegalArgumentException("resources target must be equal to bin"));
				throw new MojoExecutionException("Rascal compiler detected configuration errors");
			}

			ISourceLocation generatedSourcesLoc = MojoUtils.location(generatedSources);
			List<ISourceLocation> srcLocs = MojoUtils.locations(srcs);
			List<ISourceLocation> ignoredLocs = MojoUtils.locations(srcIgnores);
			List<ISourceLocation> libLocs = MojoUtils.locations(libs);

			if (System.getProperty("rascal.compile.skip") != null) {
				getLog().info("Skipping Rascal compiler completely");
				return;
			}

			getLog().info("configuring paths");
			for (ISourceLocation src : srcLocs) {
				getLog().info("\tregistered source location: " + src);
			}

			for (ISourceLocation ignore : ignoredLocs) {
				getLog().warn("\tignoring sources in: " + ignore);
			}

			getLog().info("Checking if any files need compilation...");

			IList todoList = getTodoList(binLoc, srcLocs, ignoredLocs);

			if (!todoList.isEmpty()) {
				getLog().info("Stale source files have been found:");
				for (IValue todo : todoList) {
					getLog().info("\t" + todo);
				}
			}
			else {
				getLog().info("No stale source files have been found, skipping compilation.");
				return;
			}

			if (enableStandardLibrary) {
				libLocs.add(URIUtil.correctLocation("lib", "rascal", ""));
			}

			// complete libraries with maven artifacts which include a META-INF/RASCAL.MF file
			MojoUtils.collectDependentArtifactLibraries(project, libLocs);

			for (ISourceLocation lib : libLocs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Paths have been configured.");

			PathConfig pcfg = new PathConfig(srcLocs, libLocs, binLoc);


			IList messages = runChecker(verbose, todoList, pcfg, generatedSourcesLoc);

			getLog().info("Checker is done, reporting errors now." 
				+ (errorsAsWarnings ? " Errors are being deescalated to warnings." : "") 
				+ (warningsAsErrors ? " Warnings are beging escalated to errors. " : ""));

			try {		
				if (!handleMessages(pcfg, messages)) {
					throw new MojoExecutionException("Rascal compiler found compile-time errors");
				}
			}
			finally {
				getLog().info("Error reporting is done.");
			}

			return;
		} 
		catch (URISyntaxException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} 
		catch (IOException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} 
		catch (InclusionScanException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} 
		catch (Throw e) {
		    getLog().error(e.getLocation() + ": " + e.getMessage());
		    getLog().error(e.getTrace().toString());
		    throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		}
		catch (Throwable e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
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


	private void safeLog(Consumer<Log> action) {
		Log log = getLog();
		synchronized (log) {
			action.accept(log);
		}
	}

	private IList runChecker(boolean verbose, IList todoList, PathConfig pcfg, ISourceLocation generatedSourcesLoc)
			throws IOException, URISyntaxException, Exception {
	    if (!parallel || todoList.size() <= 10 || parallelAmount() <= 1) {
	    	return runCheckerSingleThreaded(verbose, todoList, pcfg, generatedSourcesLoc);
		}
		else {
			return runCheckerMultithreaded(verbose, todoList, pcfg, generatedSourcesLoc);
		}
	}

	private IList runCheckerMultithreaded(boolean verbose, IList todoList, PathConfig pcfg,
			ISourceLocation generatedSourcesLoc) throws Exception {
		var streams = MojoUtils.calculateMonitor(getLog(), session);
		ConcurrentSoftReferenceObjectPool<Evaluator> evaluators = createEvaluatorPool(streams.left, streams.middle, streams.right);	

		final IConstructor pathConfig = expandPathConfig(pcfg, generatedSourcesLoc);
		final IConstructor config = evaluators.useAndReturn(e -> makeCompilerConfig(e, verbose, pathConfig));

		// split up the work into chunks and initial pre-work
		Queue<IList> parallelReports = new ConcurrentLinkedQueue<>();
		IListWriter start = VF.listWriter();
		List<IList> chunks = splitTodoList(todoList, parallelPreChecks, start);
		IList initialTodo = start.done();

		AtomicReference<Exception> failure = new AtomicReference<>(null);
		ExecutorService executor = Executors.newFixedThreadPool(parallelAmount());

		
		try {
			Semaphore prePhaseDone = new Semaphore(0);

			if (!initialTodo.isEmpty()) {
				// First run the initial modules (they will be reused a lot by the other runners)
				executor.execute(() -> {
					try {
						safeLog(l -> l.info("Running pre-phase on: " + initialTodo.stream().map(f -> "\n\t" + f).reduce("", String::concat)));
						parallelReports.add(evaluators.useAndReturn(eval ->
							runCheckerSingle(eval.getMonitor(), initialTodo, eval, config)
						));
						safeLog(l -> l.info("Finished running the pre-phase checker"));
					} 
					catch (Exception e) {
						safeLog(l -> l.info("Failure executing pre-phase:", e));
						failure.compareAndSet(null, e);
					} 
					finally {
						prePhaseDone.release(chunks.size() + 1);
					}
				});
				Thread.sleep(100); // give executor time to startup and avoid race
			}
			else {
				prePhaseDone.release(chunks.size() + 1);
			}

			// running parallel jobs for the identified chunks
			if (!chunks.isEmpty()) {
				safeLog(l -> l.debug("Preparing checker for in " + chunks.size() + " parallel threads"));
				try {
					List<ISourceLocation> binFolders = new ArrayList<>();
					List<ISourceLocation> generateSourcesFolders = new ArrayList<>();
					Semaphore done = new Semaphore(0);
					for (IList todo: chunks) {
						ISourceLocation myBin = VF.sourceLocation("tmp", "","tmp-" + System.identityHashCode(todo) + "-" + Instant.now().getEpochSecond());
						ISourceLocation mySources = VF.sourceLocation("tmp", "","tmp-srcs-" + System.identityHashCode(todo) + "-" + Instant.now().getEpochSecond());
						binFolders.add(myBin);
						generateSourcesFolders.add(mySources);
						PathConfig myConfig = new PathConfig(pcfg.getSrcs(), pcfg.getLibs().append(pcfg.getBin()), myBin);
						
						executor.execute(() -> {
							try {
								Thread.sleep(1000);  // give the other evaluator a head start
								safeLog(l -> l.debug("Starting fresh evaluator"));
								parallelReports.add(evaluators.useAndReturn(e -> {
									try { 
										prePhaseDone.acquire();
										safeLog(l -> l.debug("Starting checking chunk with " + todo.size() +  " entries"));
					
										var epcfg = expandPathConfig(myConfig, mySources);
										var myCompilerConfig = makeCompilerConfig(e, verbose, epcfg);
						
										return runCheckerSingle(e.getMonitor(), todo, e, myCompilerConfig);
									} 
									catch (InterruptedException interruptedException) {
									    return VF.list();
									}
								}));
							} 
							catch (Exception e) {
								safeLog(l -> l.error("Failure executing:", e));
								failure.compareAndSet(null, e);
							} 
							finally {
								done.release();
							}
						});
					}
					done.acquire(chunks.size());

					// now have to merge the result bins (output files of each build)
					// it's possible single modules are produced by different chunks
					// but they are guaranteed to be the same
					mergeOutputFolders(pcfg.getBin(), binFolders, false);
					// we also copy of the generated sources, but it's fine right now if they are ignored
					mergeOutputFolders(generatedSourcesLoc, generateSourcesFolders, true);

				} catch (URISyntaxException | IOException | InterruptedException e) {
					getLog().error("Failed post-processing evaluator", e);
					failure.compareAndSet(null, e);
				}
			}
			prePhaseDone.acquire();
		} 
		catch (InterruptedException e) {
		    // ignore
		} 
		finally {
			executor.shutdown();
		}

		if (failure.get() != null) {
			throw failure.get();
		}

		return parallelReports.stream().flatMap(ms -> ms.stream()).collect(VF.listWriter());
	}

	private IList runCheckerSingleThreaded(boolean verbose, IList todoList, PathConfig pcfg,
			ISourceLocation generatedSourcesLoc) throws URISyntaxException, IOException {
		getLog().info("Running checker in single threaded mode");
		var streams = MojoUtils.calculateMonitor(getLog(), session);
		try {
			Evaluator eval =  makeEvaluator(streams.left, streams.middle, streams.right, session);
			
			IConstructor pcfgCons = expandPathConfig(pcfg, generatedSourcesLoc);
			IConstructor singleConfig = makeCompilerConfig(eval, verbose, pcfgCons);

			return runCheckerSingle(eval.getMonitor(), todoList, eval, singleConfig);
		}
		finally {
			streams.left.endAllJobs();
		}
	}

	private IConstructor makeCompilerConfig(Evaluator eval, boolean verbose, IConstructor pcfgCons) {
		return (IConstructor) eval.call("rascalCompilerConfig", pcfgCons)
			.asWithKeywordParameters().setParameter("verbose", VF.bool(verbose))
			.asWithKeywordParameters().setParameter("logPathConfig", VF.bool(verbose))
			.asWithKeywordParameters().setParameter("logWrittenFiles", VF.bool(verbose))
			;
	}

	private IConstructor expandPathConfig(PathConfig pcfg, ISourceLocation generatedSourcesLoc) {
		return pcfg.asConstructor()
			.asWithKeywordParameters().setParameter("resources", pcfg.getBin())
			.asWithKeywordParameters().setParameter("generatedSources", generatedSourcesLoc);
	}

	private ConcurrentSoftReferenceObjectPool<Evaluator> createEvaluatorPool(IRascalMonitor monitor, PrintWriter err, PrintWriter out) {
		return new ConcurrentSoftReferenceObjectPool<Evaluator>(
				1, TimeUnit.MINUTES,
				1, parallelAmount(),
				() -> {
					try {
						return makeEvaluator(monitor, err, out, session);
					} catch (URISyntaxException | IOException e) {
						throw new RuntimeException(e);
					}
		});
	}

	private void mergeOutputFolders(ISourceLocation bin, List<ISourceLocation> binFolders, boolean ignoreMissing) throws IOException {
		for (ISourceLocation b : binFolders) {
			getLog().info("Copying tpls from " + b + " to " + bin);
			if (reg.isDirectory(b)) {
				mergeOutputFolders(bin, b);
			}
			else if (!ignoreMissing) {
				throw new IOException("The " + b + " folder did not exist");

			}
		}
	}
	private static void mergeOutputFolders(ISourceLocation dst, ISourceLocation src) throws IOException {
		for (String entry : reg.listEntries(src)) {
			ISourceLocation srcEntry = URIUtil.getChildLocation(src, entry);
			ISourceLocation dstEntry = URIUtil.getChildLocation(dst, entry);
			if (reg.isDirectory(srcEntry)) {
				if (!reg.exists(dstEntry)) {
					reg.mkDirectory(dstEntry);
				}
				mergeOutputFolders(dstEntry, srcEntry);
			}
			else if (!reg.exists(dstEntry)) {
				reg.copy(srcEntry, dstEntry, true, true);
			}
			try {
				reg.remove(srcEntry, true); // cleanup the temp directory
			}
			catch (Exception e) {
				// IGNORE
			}
		}
    }

	private IList runCheckerSingle(IRascalMonitor monitor, IList todoList, IEvaluator<Result<IValue>> eval, IConstructor compilerConfig) {
		try {
			return (IList) eval.call(monitor, "check", todoList, compilerConfig);
		}
		finally {
			eval.getStdErr().flush();
			eval.getStdOut().flush();
		}
	}

	private List<IList> splitTodoList(IList todoList, List<String> parallelPreList, IListWriter start) {
		Set<ISourceLocation> reserved = parallelPreList.stream().map(MojoUtils::location).collect(Collectors.toSet());
		start.appendAll(reserved);
		int chunkSize = todoList.size() / parallelAmount();
		if (chunkSize < 10) {
			chunkSize = 10;
		}
		int currentChunkSize = 0;
		IListWriter currentChunk = VF.listWriter();
		List<IList> result = new ArrayList<>();
		for (IValue todo : todoList.stream().sorted(Comparator.comparing(v -> ((ISourceLocation)v).getPath())).collect(Collectors.toList())) {
			ISourceLocation module = (ISourceLocation) todo;
			if (!reserved.contains(module)) {
				currentChunk.append(module);
				if (currentChunkSize++ > chunkSize) {
					result.add(currentChunk.done());
					currentChunkSize = 0;
					currentChunk = VF.listWriter();
				}
			}
		}
		if (currentChunkSize > 0) {
			result.add(currentChunk.done());
		}
		return result;
	}

	private IList getTodoList(ISourceLocation binLoc, List<ISourceLocation> srcLocs, List<ISourceLocation> ignoredLocs)
			throws InclusionScanException, URISyntaxException {
		StaleSourceScanner scanner = new StaleSourceScanner(100);
		scanner.addSourceMapping(new SourceMapping() {

			@Override
			public Set<File> getTargetFiles(File targetDir, String source) throws InclusionScanException {
				File file = new File(source);
				String name = file.getName();

				if (name.endsWith(".rsc")) {
					return Set.of(
						new File(targetDir, new File(file.getParentFile(), "$" + name.substring(0, name.length() - ".rsc".length()) + ".tpl").getPath())
					);
				}
				else {
					return Set.of();
				}
			}	
		});

		// TODO: currently the compiler nests all files in a /rascal root
		// It will stop doing that once the root library files have been moved into rascal::
		binLoc = URIUtil.getChildLocation(binLoc, "rascal");

		Set<File> staleSources = new HashSet<>();
		for (ISourceLocation src : srcLocs) {
			staleSources.addAll(scanner.getIncludedSources(new File(src.getURI()), new File(binLoc.getURI())));
		}

		IListWriter filteredStaleSources = ValueFactoryFactory.getValueFactory().listWriter();

		for (File file : staleSources) {
			ISourceLocation loc = URIUtil.createFileLocation(file.getAbsolutePath());

			if (ignoredLocs.stream().noneMatch(l -> isIgnoredBy(l, loc))) {
				filteredStaleSources.append(loc);
			}
		}

		return filteredStaleSources.done();
	}

	private boolean isIgnoredBy(ISourceLocation prefix, ISourceLocation loc) {
		assert prefix.getScheme().equals("file");
		assert loc.getScheme().equals("file");

		String prefixPath = prefix.getPath();
		String locPath = loc.getPath();

		return locPath.startsWith(prefixPath);
	}

	private boolean handleMessages(PathConfig pcfg, IList moduleMessages) throws MojoExecutionException {
		int maxLine = 0;
		int maxColumn = 0;
		boolean hasErrors = false;

		for (IValue val : moduleMessages) {
			ISet messages =  (ISet) ((IConstructor) val).get("messages");

			for (IValue error : messages) {
				ISourceLocation loc = (ISourceLocation) ((IConstructor) error).get("at");
				if (loc.hasLineColumn()) {
					maxLine = Math.max(loc.getBeginLine(), maxLine);
					maxColumn = Math.max(loc.getBeginColumn(), maxColumn);
				} else {
					getLog().error("loc without line/column: " + loc);
				}
			}
		}


		int lineWidth = (int) Math.log10(maxLine + 1) + 1;
		int colWidth = (int) Math.log10(maxColumn + 1) + 1;

		for (IValue val : moduleMessages) {
			ISourceLocation module = (ISourceLocation) ((IConstructor) val).get("src");
			ISet messages =  (ISet) ((IConstructor) val).get("messages");

			if (!messages.isEmpty()) {
				getLog().info("Warnings and errors for " + abbreviate(module.top(), pcfg));
			}

			Stream<IConstructor> sortedStream = messages.stream()
				.map(IConstructor.class::cast)
				.sorted((m1, m2) -> {
					ISourceLocation l1 = (ISourceLocation) m1.get("at");
					ISourceLocation l2 = (ISourceLocation) m2.get("at");
					
					if (l1.getBeginLine() == l2.getBeginLine()) {
						return Integer.compare(l1.getBeginColumn(), l2.getBeginColumn());
					}
					else {
						return Integer.compare(l1.getBeginLine(), l2.getBeginLine());
					}
				});

			for (IConstructor msg : sortedStream.collect(Collectors.toList())) {
				String type = msg.getName();
				boolean isError = type.equals("error");
				boolean isWarning = type.equals("warning");

				hasErrors |= isError || warningsAsErrors;

				ISourceLocation loc = (ISourceLocation) msg.get("at");
				int col = 0;
				int line = 0;
				if (loc.hasLineColumn()) {
					col = loc.getBeginColumn();
					line = loc.getBeginLine();
				}

				String output
				= abbreviate(loc, pcfg)
				+ ":"
				+ String.format("%0" + lineWidth + "d", line)
				+ ":"
				+ String.format("%0" + colWidth + "d", col)
				+ ": "
				+ ((IString) msg.get("msg")).getValue();

				if (isError) {
					// align "[ERROR]" with "[WARNING]" by adding two spaces
					getLog().error("  " + output);
				}
				else if (isWarning) {
					getLog().warn(output);
				}
				else {
					// align "[INFO]" with "[WARNING]" by adding three spaces
					getLog().info("   " + output);
				}
			}
		}

		return !hasErrors || errorsAsWarnings;
	}

	private static String abbreviate(ISourceLocation loc, PathConfig pcfg) {
		for (IValue src : pcfg.getSrcs()) {
			String path = ((ISourceLocation) src).getURI().getPath();

			if (loc.getPath().startsWith(path)) {
				return loc.getPath().substring(path.length());
			}
		}

		return loc.getPath();
	}
}
