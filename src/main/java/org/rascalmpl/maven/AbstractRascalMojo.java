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

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import java.lang.Process;
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

	@Parameter(property = "ignores", required = false )
	protected List<File> ignores;

	@Parameter(property = "libs", required = false )
	protected List<File> libs;

	@Parameter(defaultValue="false", property= "verbose", required=true)
	protected boolean verbose;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	protected MavenSession session;

	@Parameter(defaultValue = "0.41.0-RC41", required = false, readonly = false)
	protected String bootstrapRascalVersion;

	@SuppressWarnings("deprecation") // Can't get @Parameter to work for the pluginManager.
	@Component
	protected BuildPluginManager pluginManager;

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
			getLog().info("The Rascal runtime was resolved at " + cachedRascalRuntime);
		}

		return cachedRascalRuntime;
	}

	protected boolean isRascalProject() {
		return project.getGroupId().equals("org.rascalmpl") && project.getArtifactId().equals("rascal");
	}

	/**
	 * Converts a list of files to a string;Of;Paths using the OS's native path separator
	 */
	protected String files(List<File> list) {
		return list.stream()
			.map(Object::toString)
			.collect(Collectors.joining(File.pathSeparator));
	}

	protected void setExtraParameters() {
		// do nothing yet
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

			for (File ignore : ignores) {
				getLog().warn("\tignoring sources in: " + ignore);
			}

			getLog().info("Checking if any files need compilation...");

			libs.addAll(collectDependentArtifactLibraries(project));

			for (File lib : libs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Paths have been configured.");

			setExtraParameters();

			runMain(
				verbose,
				"",
				srcs,
				ignores,
				libs,
				generatedSources,
				bin,
				extraParameters,
				true,
				1)
				.waitFor();

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

				if (bootstrapRascalVersion.endsWith("SNAPSHOT")) {
					throw new IllegalArgumentException("The rascalBootstrapVersion configuration parameter must not be a SNAPSHOT release, because of the required reproducibility of any Rascal release. The Maven build will bail out now.");
				}

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
		return new DefaultArtifactVersion("0.41.0-RC30");
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

	private static long GB(int a) {
		return a * (1024l*1024l*1024l);
	}

	protected Process runMain(boolean verbose, String moreClasspath, List<File> srcs, List<File> ignores, List<File> libs, File generated, File bin, Map<String, String> extraParameters, boolean inheritIO, int numProcesses) throws IOException {
		String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

		List<String> command = new LinkedList<>();
		command.add(javaBin);

		System.getProperties().forEach((key, value) -> {
			// Do not propagate `user.dir`, since that breaks multi-module maven projects
			if (!key.equals("user.dir")) {
				command.add("-D" + key + "=" + value);
			}
		});

		// give it enough memory, but not more than is available.
		// we divide the total memory by the number of processes we are running in parallel and take 10% for the OS.
		long totalMemoryKilobytes = 9 * (systemInformation.getHardware().getMemory().getTotal() / (1000 * numProcesses * 10));
		long requiredMemoryKilobytes = GB(2);

		command.add("-Xmx" + Math.min(totalMemoryKilobytes, requiredMemoryKilobytes) + "k");

		// we put the entire pathConfig on the commandline, and finally the todoList for compilation.
		command.add("-cp");
		command.add(getRascalRuntime().toString() + (moreClasspath.isEmpty() ? "" : File.pathSeparator + moreClasspath));

		assert mainClass != null : "mainClass is null";

		command.add(mainClass);

		if (mainClass.endsWith("RascalShell")) {
			if (mainModule != null && !mainModule.isEmpty()) {
				// the main module parameter is specific to the RascalShell command
				// here for backward compatibility reasons for older client-owned shell scripts
				command.add(mainModule);
			}
		}

		if (!mainClass.endsWith("RascalShell")) {
			if (!srcs.isEmpty()) {
				command.add("-srcs");
				command.add(files(srcs));
			}

			if (!ignores.isEmpty()) {
				command.add("-ignores");
				command.add(files(ignores));
			}

			if (!libs.isEmpty()) {
				command.add("-libs");
				command.add(files(libs));
			}

			command.add("-bin");
			assert bin != null : "bin is null";
			command.add(bin.toString());

			command.add("-generatedSources");
			assert generatedSources != null : "generatedSourced is null";
			command.add(generated.toString());

			for (Entry<String, String> e : extraParameters.entrySet()) {
				command.add("-" + e.getKey());
				assert e.getValue() != null : "value with " + e.getKey() + " is null in extraParameters";
				command.add(e.getValue());
			}

			if (verbose) {
				command.add("-verbose");
			}
		}

		assert command.stream().map(Objects::nonNull).allMatch(b -> b) : "command had a null parameter";

		getLog().debug("Java exec: " + command.get(0));
		getLog().debug("Starting process:\n\t java " +
			command.stream()
			.skip(1)
			.map(s -> s.replace("\n", "\\\\n"))
			.map(s -> "'" + s + "'")
			.collect(Collectors.joining(" ")));

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

	protected List<File> getTodoList(File binLoc, List<File> srcLocs, List<File> ignoredLocs, String dirtyExtension, String binaryExtension, String binaryPrefix) throws InclusionScanException, URISyntaxException {
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

		binLoc = new File(binLoc, binaryPrefix);

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
