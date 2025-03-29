/*******************************************************************************
 * Copyright (c) 2019-2025 CWI
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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;

import oshi.SystemInfo;

import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;


/**
 * Abstract Maven Goal for Rascal tools. All tools (checker, tutor, compiler)
 * are configured via PathConfig and their main function.
 *
 * All tools run in the JVM with the rascal dependency loaded.
 */
public abstract class AbstractRascalMojo extends AbstractMojo
{
	@Parameter(defaultValue="${project}", readonly=true, required=true)
	protected MavenProject project;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
	protected File bin;

	@Parameter(defaultValue = "${project.build.directory}/generatedSources", property = "generatedSources", required = true)
	protected File generatedSources;

	@Parameter(property = "srcs", required = true )
	protected List<File> srcs;

	@Parameter(property = "srcIgnores", required = false )
	protected List<File> srcIgnores;

	@Parameter(property = "libs", required = false )
	protected List<File> libs;

	@Parameter(defaultValue="false", property= "verbose", required=true)
	protected boolean verbose;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
  	private MavenSession session;

	@Parameter(defaultValue = "0.41.0-RC21", required = false, readonly = true)
	protected String bootstrapRascalVersion;

	@SuppressWarnings("deprecation") // Can't get @Parameter to work for the pluginManager.
	@Component
	private BuildPluginManager pluginManager;

	@Parameter(defaultValue="")
	protected String mainModule;

	/**
	 * Which `-Drascal.skipTag.skip` to use
	 */
	protected final String skipTag;

	/**
	 * Class with `static int main(String[] arg)` method to call
	 */
	protected final String mainClass;

	/**
	 * If there are more than the normal PathConfig parameters
	 * they can be added here.
	 */
	protected Map<String, String> extraParameters = new HashMap<>();

	/**
	 * The Rascal runtime jar that will be used to load classes (including the main class) from.
	 * This is where bootstrap issues are resolved.
	 */
	protected Path cachedRascalRuntime = null;

	// keeping this field to speed up subsequent (slow but cached) calls to system information
	protected SystemInfo systemInformation = new SystemInfo();

	public AbstractRascalMojo(String mainClass, String skipTag) {
		this.mainClass = mainClass;
		this.skipTag = skipTag;
	}

	protected Path getRascalRuntime() {
		if (cachedRascalRuntime == null) {
			cachedRascalRuntime = detectedDependentRascalArtifact(getLog(), project, session);
		}

		return cachedRascalRuntime;
	}

	/**
	 * Converts a list of files to a string;Of;Paths using the OS's native path separator
	 */
	protected String files(List<File> list) {
		return list.stream()
			.map(Object::toString)
			.collect(Collectors.joining(File.pathSeparator));
	}

	@Override
	public void execute() throws MojoExecutionException {
		try {
			if (System.getProperty("rascal." + skipTag + ".skip") != null) {
				getLog().info("Skipping " + getClass().getName() + " completely");
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

			libs.addAll(collectDependentArtifactLibraries(project));

			for (File lib : libs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Paths have been configured.");

			runMain(verbose, srcs, libs, generatedSources, bin, extraParameters, true).waitFor();

			return;
		}
		catch (InterruptedException e) {
			throw new MojoExecutionException("nested " + mainClass + " was killed", e);
		}
		catch (Throwable e) {
			throw new MojoExecutionException("error launching " + mainClass, e);
		}
	}

	/**
	 * Finds the rascal.jar file that the pom.xml depends on.
	 * When the current project is rascal itself we resolve to a declared bootstrap
	 * dependency.
	 */
	protected Path detectedDependentRascalArtifact(Log log, MavenProject project, MavenSession session) {
		try {
			if (project.getGroupId().equals("org.rascalmpl") && project.getArtifactId().equals("rascal")) {
				// we are in bootstrap mode and must find a previously released
				// version to kick-off from
				log.info("Maven Rascal Mojo detected rascal project self-application. Downloading the configured bootstrap rascal-" + bootstrapRascalVersion + ".jar");
				log.info("Find <rascalBootstrapVersion>" + bootstrapRascalVersion + "</rascalBootstrapVersion> in rascal/pom.xml");
				return installBootstrapRascalVersion(project, session);
			}

			for (Object o : project.getArtifacts()) {
				Artifact a = (Artifact) o;

				if (a.getGroupId().equals("org.rascalmpl") && a.getArtifactId().equals("rascal")) {
					File file = a.getFile().getAbsoluteFile();

					if (a.getSelectedVersion().compareTo(getReferenceRascalVersion()) >= 0) {
						return file.toPath();
					}
					else {
						log.warn("Rascal version in pom.xml dependency is too old for this Rascal maven plugin. " + getReferenceRascalVersion() + " or later expected.");
						log.warn("Downloading and using a newer version org.rascalmpl:rascal:" + bootstrapRascalVersion + "; please add it to the pom.xml");
						return installBootstrapRascalVersion(project, session);
					}
				}
			}

			throw new MojoExecutionException("Pom.xml is missig a dependency on org.rascalmpl:rascal:" + getReferenceRascalVersion() + " (or later).");
		}
		catch (OverConstrainedVersionException e) {
			log.error("Rascal version is over-constrained (impossible to resolve). Expected " + getReferenceRascalVersion() + " or later. Have to abort.");
			throw new RuntimeException(e);
		}
		catch (MojoExecutionException e) {
			log.error("Unable to just-in-time-install the required (bootstrap) version of Rascal. Have to abort.");
			throw new RuntimeException(e);
		}
	}

	protected List<File> collectDependentArtifactLibraries(MavenProject project) throws URISyntaxException, IOException {
		List<File> libs = new LinkedList<>();

		for (Object o : project.getArtifacts()) {
			Artifact a = (Artifact) o;
			File file = a.getFile().getAbsoluteFile();

			libs.add(file);
		}

		return libs;
	}

	protected final ArtifactVersion getReferenceRascalVersion() {
		return new DefaultArtifactVersion("0.41.0-RC16");
	}

	protected Path installBootstrapRascalVersion(MavenProject project, MavenSession session) throws MojoExecutionException {
		// download the boostrap version from our maven site
		executeMojo(
			plugin(
				"org.apache.maven.plugins",
				"maven-dependency-plugin",
				"3.1.1"
			),
			goal("get"),
			configuration(
      			  element("artifact", "org.rascalmpl:rascal:" + bootstrapRascalVersion)
    		),
			executionEnvironment(
				project,
				session,
				pluginManager
			)
		);

		// resolve the jar file in the .m2 repository
		return Path.of(session.getSettings().getLocalRepository(),
			"org", "rascalmpl", "rascal", bootstrapRascalVersion, "rascal-" + bootstrapRascalVersion + ".jar");
	}

	protected Process runMain(boolean verbose, List<File> srcs, List<File> libs, File generated, File bin, Map<String, String> extraParameters, boolean inheritIO) throws IOException {
		String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

		List<String> command = new LinkedList<String>();
		command.add(javaBin);

		System.getProperties().forEach((key, value) -> {
			command.add("-D" + key + "=" + value);
		});

		// give it enough memory, but not more than is available.
		long totalMemoryKilobytes = systemInformation.getHardware().getMemory().getTotal() / 1000;
		long requiredMemoryKilobytes = 2000 * 1000;

		command.add("-Xmx" + Math.min(totalMemoryKilobytes, requiredMemoryKilobytes) + "k");

		// we put the entire pathConfig on the commandline, and finally the todoList for compilation.
		command.add("--illegal-access=deny");
		command.add("-cp");
		command.add(getRascalRuntime().toString());
		command.add(mainClass);
		if (mainModule != null && !mainModule.isEmpty()) {
			command.add(mainModule);
		}
		command.add("-srcs");
		command.add(files(srcs));
		command.add("-libs");
		command.add(files(libs));
		command.add("-bin");
		command.add(bin.toString());
		command.add("-generatedSources");
		command.add(generated.toString());

		for (Entry<String, String> e : extraParameters.entrySet()) {
			command.add("-" + e.getKey());
			command.add(e.getValue());
		}

		if (verbose) {
			command.add("-verbose");
		}

		ProcessBuilder p = new ProcessBuilder(command);

		if (inheritIO) {
			// everything merges with the current process' streams
			p.inheritIO();
		}
		else {
			// stderr goes to stdout as well
			p.redirectErrorStream(true);
		}

		return p.start();
	}

	protected List<File> getTodoList(File binLoc, List<File> srcLocs, List<File> ignoredLocs, String dirtyExtension, String binaryExtension) throws InclusionScanException, URISyntaxException {
		StaleSourceScanner scanner = new StaleSourceScanner(100);
		scanner.addSourceMapping(new SourceMapping() {

			@Override
			public Set<File> getTargetFiles(File targetDir, String source) throws InclusionScanException {
				File file = new File(source);
				String name = file.getName();

				if (name.endsWith("." + dirtyExtension)) {
					return Set.of(
						new File(targetDir, new File(file.getParentFile(), "$" + name.substring(0, name.length() - ("." + dirtyExtension).length()) + "." + binaryExtension).getPath())
					);
				}
				else {
					return Set.of();
				}
			}
		});

		binLoc = new File(binLoc, "rascal");

		Set<File> staleSources = new HashSet<>();
		for (File src : srcs) {
			staleSources.addAll(scanner.getIncludedSources(src, binLoc));
		}

		List<File> filteredStaleSources = new LinkedList<>();

		for (File file : staleSources) {
			if (ignoredLocs.stream().noneMatch(l -> isIgnoredBy(l, file))) {
				filteredStaleSources.add(file);
			}
		}

		return filteredStaleSources;
	}

	protected boolean isIgnoredBy(File prefix, File loc) {
		String prefixPath = prefix.toString();
		String locPath = loc.toString();

		return locPath.startsWith(prefixPath);
	}

	@FunctionalInterface
	protected interface FunctionWithException<T, R, E extends Exception> {
    	R apply(T t) throws E;
	}

	protected <T, R, E extends Exception> Function<T, R> handleExceptions(FunctionWithException<T, R, E> fe) {
        return arg -> {
            try {
                return fe.apply(arg);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
		};
	}

}
