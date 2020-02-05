/*******************************************************************************
 * Copyright (c) 2019 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 */
package org.rascalmpl.maven;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
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
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.rascalmpl.debug.IRascalMonitor;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.library.util.PathConfig;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
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
import io.usethesource.vallang.io.StandardTextReader;

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
 * TODO This Mojo always requires a 'boot' parameter which points to the file location of the
 * source of the compiler. After the bootstrap, this parameter will become optional.
 * 
 */
@Mojo(name="compile", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CompileRascalMojo extends AbstractMojo
{
	private static final String UNEXPECTED_ERROR = "unexpected error during Rascal compiler run";
	private static final String MAIN_COMPILER_MODULE = "lang::rascalcore::check::Checker";
	private static final String INFO_PREFIX_MODULE_PATH = "\trascal module path addition: ";
	private static final URIResolverRegistry reg = URIResolverRegistry.getInstance();
	
	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
	private String bin;

	@Parameter(property = "srcs", required = true )
	private List<String> srcs;
	
	@Parameter(property = "srcIgnores", required = false )
	private List<String> srcIgnores;    

	@Parameter(property = "libs", required = false )
	private List<String> libs;

	@Parameter(property = "errorsAsWarnings", required = false, defaultValue = "false" )
	private boolean errorsAsWarnings;

	@Parameter(property = "warningsAsErrors", required = false, defaultValue = "false" )
	private boolean warningsAsErrors;

	@Parameter(property="enableStandardLibrary", required = false, defaultValue="true")
	private boolean enableStandardLibrary;
	
	private final PrintWriter err = new PrintWriter(System.err);
	private final PrintWriter out = new PrintWriter(System.out);

	private MojoRascalMonitor monitor;

	private Evaluator makeEvaluator(PathConfig pcfg) throws URISyntaxException, FactTypeUseException, IOException {
		getLog().info("start loading the compiler");
		GlobalEnvironment heap = new GlobalEnvironment();
		Evaluator eval = new Evaluator(ValueFactoryFactory.getValueFactory(), err, out, new ModuleEnvironment("***MVN Rascal Compiler***", heap), heap);

		URL vallangJarFile = IValueFactory.class.getProtectionDomain().getCodeSource().getLocation();
		eval.getConfiguration().setRascalJavaClassPathProperty(new File(vallangJarFile.toURI()).toString());

		monitor = new MojoRascalMonitor(getLog());
		eval.setMonitor(monitor);

		getLog().info(INFO_PREFIX_MODULE_PATH + "|lib://typepal/|");
        eval.addRascalSearchPath(URIUtil.correctLocation("lib", "typepal", ""));

		getLog().info(INFO_PREFIX_MODULE_PATH + "|lib://rascal-core/|");
		eval.addRascalSearchPath(URIUtil.correctLocation("lib", "rascal-core", ""));
		
		getLog().info(INFO_PREFIX_MODULE_PATH + "|std:///|");
		eval.addRascalSearchPath(URIUtil.rootLocation("std"));

		getLog().info(INFO_PREFIX_MODULE_PATH + pcfg.getBoot());
		eval.addRascalSearchPath(pcfg.getBoot());
		
		getLog().info("\timporting " + MAIN_COMPILER_MODULE);
		eval.doImport(monitor, MAIN_COMPILER_MODULE);

		getLog().info("done loading the compiler");

		return eval;
	}

	public void execute() throws MojoExecutionException {
		try {
			ISourceLocation binLoc = location(bin);
			List<ISourceLocation> srcLocs = locations(srcs);
			List<ISourceLocation> ignoredLocs = locations(srcIgnores);
			List<ISourceLocation> libLocs = locations(libs);
			
			getLog().info("configuring paths");
			for (ISourceLocation src : srcLocs) {
				getLog().info("\tregistered source location: " + src);
			}
			
			for (ISourceLocation ignore : ignoredLocs) {
				getLog().warn("\tignoring sources in: " + ignore);
			}
			
			getLog().info("checking if any files need compilation");

			IList todoList = getTodoList(binLoc, srcLocs, ignoredLocs);

			if (!todoList.isEmpty()) {
				getLog().info("stale source files have been found:");
				for (IValue todo : todoList) {
					getLog().info("\t" + todo);
				}
			}
			else {
				getLog().info("no stale source files have been found, skipping compilation.");
				return;
			}
			
			if (enableStandardLibrary) {
				// TODO: add stdlib location resolver to Rascal project, for now: |manifest:///| will do since it resolves to the same root location
				// in the rascal-maven-plugin (shaded) jar
				libLocs.add(URIUtil.rootLocation("manifest"));
			}
			
			// complete libraries with maven artifacts which include a META-INF/RASCAL.MF file
			collectDependentArtifactLibraries(libLocs);
			
			for (ISourceLocation lib : libLocs) {
				getLog().info("\tregistered library location: " + lib);
			}
			
			getLog().info("paths have been configured");
			
			PathConfig pcfg = new PathConfig(srcLocs, libLocs, binLoc);
			Evaluator eval = makeEvaluator(pcfg);

			
			IConstructor config = pcfg.asConstructor();
			
			IList messages = (IList) eval.call(monitor, "check", todoList, config);

			getLog().info("checker is done, reporting errors now.");
			handleMessages(pcfg, messages);
			getLog().info("error reporting done");
			
			return;
		} catch (URISyntaxException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (IOException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (InclusionScanException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		}
	}

	private void collectDependentArtifactLibraries(List<ISourceLocation> libLocs) throws URISyntaxException, IOException {
		for (Object o : project.getArtifacts()) {
			Artifact a = (Artifact) o;
			File file = a.getFile().getAbsoluteFile();
			ISourceLocation jarLoc = RascalManifest.jarify(location(file.toString()));
			
			if (reg.exists(URIUtil.getChildLocation(jarLoc, "META-INF/RASCAL.MF"))) {
				libLocs.add(jarLoc);
			}
		}
	}

	private IList getTodoList(ISourceLocation binLoc, List<ISourceLocation> srcLocs, List<ISourceLocation> ignoredLocs)
			throws InclusionScanException, URISyntaxException {
		StaleSourceScanner scanner = new StaleSourceScanner(100);
		scanner.addSourceMapping(new SuffixMapping(".rsc", ".tpl"));
		
		Set<File> staleSources = new HashSet<>();
		for (ISourceLocation src : srcLocs) {
			staleSources.addAll(scanner.getIncludedSources(new File(src.getURI()), new File(binLoc.getURI())));
		}
		
		IListWriter filteredStaleSources = ValueFactoryFactory.getValueFactory().listWriter();
		
		OUTER:for (File file : staleSources) {
			ISourceLocation loc = URIUtil.createFileLocation(file.getAbsolutePath());
			
			for (ISourceLocation iloc : ignoredLocs) {
				if (isIgnoredBy(iloc, loc)) {
					continue OUTER;
				}
			}
			
			filteredStaleSources.append(loc);
		}
		
		IList todoList = filteredStaleSources.done();
		return todoList;
	}
	
	private boolean isIgnoredBy(ISourceLocation prefix, ISourceLocation loc) {
		assert prefix.getScheme().equals("file");
		assert loc.getScheme().equals("file");
		
		String prefixPath = prefix.getPath();
		String locPath = loc.getPath();
		
		return locPath.startsWith(prefixPath);
	}

	private void handleMessages(PathConfig pcfg, IList moduleMessages) throws MojoExecutionException {
		int maxLine = 0;
		int maxColumn = 0;
		boolean hasErrors = false;

		for (IValue val : moduleMessages) {
			ISet messages =  (ISet) ((IConstructor) val).get("messages");
			
			for (IValue error : messages) {
				ISourceLocation loc = (ISourceLocation) ((IConstructor) error).get("at");
				maxLine = Math.max(loc.getBeginLine(), maxLine);
				maxColumn = Math.max(loc.getBeginColumn(), maxColumn);
			}
		}


		int lineWidth = (int) Math.log10(maxLine + 1) + 1;
		int colWidth = (int) Math.log10(maxColumn + 1) + 1;

		for (IValue val : moduleMessages) {
			ISourceLocation module = (ISourceLocation) ((IConstructor) val).get("src");
			ISet messages =  (ISet) ((IConstructor) val).get("messages");

			if (!messages.isEmpty()) {
				getLog().info("Warnings and errors for " + module);
			}
			
			for (IValue error : messages) {
				IConstructor msg = (IConstructor) error;
				String type = msg.getName();
				boolean isError = type.equals("error");
				boolean isWarning = type.equals("warning");

				hasErrors |= isError || warningsAsErrors;
				
				ISourceLocation loc = (ISourceLocation) msg.get("at");
				int col = loc.getBeginColumn();
				int line = loc.getBeginLine();

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

		if (hasErrors && !errorsAsWarnings) {
			throw new MojoExecutionException("Rascal compiler found compile-time errors");
		}
		
		return;
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

	private List<ISourceLocation> locations(List<String> files) throws URISyntaxException, FactTypeUseException, IOException {
		List<ISourceLocation> result = new ArrayList<ISourceLocation>(files.size());

		for (String f : files) {
			result.add(location(f));
		}

		return result;
	}

	private ISourceLocation location(String file) throws URISyntaxException, FactTypeUseException, IOException {
		if (file.startsWith("|") && file.endsWith("|")) {
			return (ISourceLocation) new StandardTextReader().read(ValueFactoryFactory.getValueFactory(), new StringReader(file));
		}
		else {
			return URIUtil.createFileLocation(file);
		}
	}

	private static class MojoRascalMonitor implements IRascalMonitor {
		private final Log log;

		public MojoRascalMonitor(Log log) {
			this.log = log;
		}

		public void startJob(String name) {
		}

		public void startJob(String name, int totalWork) {
			startJob(name);
		}

		public void startJob(String name, int workShare, int totalWork) {
			startJob(name);
		}

		public void event(String name) {
		}

		public void event(String name, int inc) {
			event(name);

		}

		public void event(int inc) {

		}

		public int endJob(boolean succeeded) {
			return 0;
		}

		public boolean isCanceled() {
			return false;
		}

		public void todo(int work) {

		}

		public void warning(String message, ISourceLocation src) {
			log.warn(src.toString() + ": " + message);
		}
	}
}
