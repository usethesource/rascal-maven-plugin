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
import java.util.LinkedList;
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
import java.util.function.Function;
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
 * Abstract Maven Goal for Rascal tools. All tools (checker, tutor, compiler)
 * are configured via PathConfig and their main function.
 *
 * All tools run in the JVM with the rascal
 * dependency loaded (if present in the pom). Otherwise we use the Rascal dependency
 * of the maven-plugin on Rascal.
 *
 */
@Mojo(name="compile", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public abstract class AbstractRascalMojo extends AbstractMojo
{
	private static final URIResolverRegistry reg = URIResolverRegistry.getInstance();
	private static final IValueFactory VF = ValueFactoryFactory.getValueFactory();

	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
	private String bin;

	@Parameter(defaultValue = "${project.build.directory}/generatedSources", property = "generatedSources", required = true)
	private String generatedSources;

	@Parameter(property = "srcs", required = true )
	private List<String> srcs;

	@Parameter(property = "srcIgnores", required = false )
	private List<String> srcIgnores;

	@Parameter(property = "libs", required = false )
	private List<String> libs;

	@Parameter(defaultValue="false", property= "verbose", required=true)
	private boolean verbose;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
  	private MavenSession session;

	private final String mainClass;

	public AbstractRascalMojo(String mainClass) {
		this.mainClass = mainClass;
	}

	public void execute() throws MojoExecutionException {
		try {
			if (System.getProperty("rascal.compile.skip") != null) {
				getLog().info("Skipping " + getClass().getName() + " completely");
				return;
			}

			ISourceLocation binLoc = MojoUtils.location(bin);
			ISourceLocation generatedSourcesLoc = MojoUtils.location(generatedSources);
			List<ISourceLocation> srcLocs = MojoUtils.locations(srcs);
			List<ISourceLocation> ignoredLocs = MojoUtils.locations(srcIgnores);
			List<ISourceLocation> libLocs = MojoUtils.locations(libs);

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

			// complete libraries with maven artifacts which include a META-INF/RASCAL.MF file
			MojoUtils.collectDependentArtifactLibraries(project, libLocs);

			for (ISourceLocation lib : libLocs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Paths have been configured.");

			runMain(verbose, todoList, srcLocs, libLocs, generatedSourcesLoc, binLoc);

			return;
		}
		catch (Throwable e) {
			throw new MojoExecutionException("error launching " + mainClass, e);
		}
	}

	private void runMain(boolean verbose, IList todoList, List<ISourceLocation> srcs, List<ISourceLocation> libs, ISourceLocation generated, ISourceLocation bin) throws IOException {
		var rascalLoc = MojoUtils.detectedDependentRascalArtifact(project);
		assert rascalLoc.getScheme().equals("file");

		String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

        try {
            List<String> command = new LinkedList<String>();
            command.add(javaBin);

            System.getProperties().forEach((key, value) -> {
                command.add("-D" + key + "=" + value);
            });

			// we put the entire pathConfig on the commandline, and finally the todoList for compilation.
            command.add("-cp");
            command.add(rascalLoc.getPath());
            command.add(mainClass);
			command.add("-srcs");
			command.add(srcs.stream().map(Object::toString).collect(Collectors.joining(File.pathSeparator))));
			command.add("-libs");
			command.add(libs.stream().map(Object::toString).collect(Collectors.joining(File.pathSeparator))));
			command.add("-bin");
			command.add(bin.toString());
			command.add("-generatedSources");
			command.add(generated.toString());
			command.add("-modules");
			command.add(todoList.stream().map(Object::toString).collect(Collectors.joining(File.pathSeparator)));

			if (verbose) {
				command.add("-verbose");
			}

            ProcessBuilder builder = new ProcessBuilder(command);
            Process process = builder.inheritIO().start();
            process.waitFor();
        } catch (IOException e) {
            getLog().error(e);
        } catch (InterruptedException e) {
            getLog().warn(e);
        }
	}


	protected IList getTodoList(ISourceLocation binLoc, List<ISourceLocation> srcLocs, List<ISourceLocation> ignoredLocs)
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

}
