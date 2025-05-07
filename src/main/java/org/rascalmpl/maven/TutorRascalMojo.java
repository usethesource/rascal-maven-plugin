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

import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Maven Goal for Rascal Tutor Documentation compilation. The input is a list of
 * Rascal source folders, and course folders, and the output is for each module
 * a markdown file and for each markdown file in a source course an output markdown
 * file. The compiler also copies images from source to a target assets folder.
 * Also a list of errors and warnings is printed on stderr.
 *
 * Note that during the compilation of documentation, the Rascal interpreter is
 * used to execute code examples for inclusion in the docs. This uses the REPL interface.
 */
@Mojo(name="tutor", inheritByDefault=false, defaultPhase = LifecyclePhase.GENERATE_RESOURCES, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class TutorRascalMojo extends AbstractRascalMojo
{
	@Parameter(property="license", required=false, defaultValue="${project.basedir}/LICENSE.md")
	private String license;

	@Parameter(property="citation", required=false, defaultValue="${project.basedir}/CITATION.md")
	private String citation;

	@Parameter(property="sources", required=false, defaultValue="http://github.com/usethesource/${project.name}/blob/main")
	private String sources;

	@Parameter(property="funding", required=false, defaultValue="${project.basedir}/FUNDING.md")
	private String funding;

	@Parameter(property="issues", required=false, defaultValue="http://github.com/usethesource/${project.name}/issues")
	private String issues;

	@Parameter(property="isPackageCourse", required=false, defaultValue="true")
	private boolean isPackageCourse;

	@Parameter(property="releaseNotes", required=false, defaultValue="${project.basedir}/RELEASE-NOTES.md")
	private String releaseNotes;

	@Parameter(property="errorsAsWarnings", required=false, defaultValue="false")
	private boolean errorsAsWarnings;

	@Parameter(property="warningsAsErrors", required=false, defaultValue="false")
	private boolean warningsAsErrors;

	@Parameter(defaultValue = "0.2.1", required = false, readonly = false)
	private String screenShotFeatureVersion;

	public TutorRascalMojo() {
		super("org.rascalmpl.shell.RascalTutorCompile", "tutor");
	}

	/**
	 * Enables tutor bootstrap
	 */
	protected Path getRascalRuntime() {
		if (isRascalProject()) {
			// bootstrap mode
			return bin.toPath();
		}

		return super.getRascalRuntime();
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

			var deps = collectDependentArtifactLibraries(project);
			libs.addAll(deps);

			for (File lib : libs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Paths have been configured.");

			extraParameters.putAll(Map.of(
				"license", license,
				"citation", citation,
				"funding", funding,
				"releaseNotes", releaseNotes,
				"isPackageCourse", Boolean.toString(isPackageCourse),
				"errorsAsWarnings", Boolean.toString(errorsAsWarnings),
				"warningsAsErrors", Boolean.toString(warningsAsErrors)
			));

			if (isPackageCourse) {
				extraParameters.put("packageName", project.getId());
			}

			String screenshotter = null;
			try {
				screenshotter = installScreenshotFeature(project, session).toString();
			}
			catch (MojoExecutionException e) {
				getLog().warn("Could not install rascal-tutor-screenshot feature. Ignoring.", e);
				screenshotter = "";
			}

			deps.add(0, bin);

			int exitCode = runMain(
				verbose,
				screenshotter + (isRascalProject() ? (File.pathSeparator + deps.stream().map(Object::toString).collect(Collectors.joining(File.pathSeparator))) : ""),
				srcs,
				srcIgnores,
				libs,
				generatedSources,
				bin,
				extraParameters,
				true,
				1)
				.waitFor();

			if (exitCode != 0) {
				throw new MojoExecutionException("tutor returned non-zero exit status");
			}

		}
		catch (InterruptedException e) {
			throw new MojoExecutionException("nested " + mainClass + " was killed", e);
		}
		catch (Throwable e) {
			throw new MojoExecutionException("error launching " + mainClass, e);
		}
	}

	protected Path installScreenshotFeature(MavenProject project, MavenSession session) throws MojoExecutionException {
		// download the boostrap version from our maven site
		executeMojo(
			plugin(
				"org.apache.maven.plugins",
				"maven-dependency-plugin",
				"3.1.1"
			),
			goal("get"),
			configuration(
      			  element("artifact", "org.rascalmpl:rascal-tutor-screenshot:" + screenShotFeatureVersion)
    		),
			executionEnvironment(
				project,
				session,
				pluginManager
			)
		);

		// resolve the jar file in the .m2 repository
		return Path.of(session.getSettings().getLocalRepository(),
			"org", "rascalmpl", "rascal-tutor-screenshot", screenShotFeatureVersion, "rascal-tutor-screenshot-" + screenShotFeatureVersion + ".jar");
	}
}
