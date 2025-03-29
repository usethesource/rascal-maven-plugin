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
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

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
@Mojo(name="tutor", inheritByDefault=false, defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CompileRascalDocumentation extends AbstractRascalMojo
{
	@Parameter(property="license", required=false, defaultValue="${project.basedir}/LICENSE.md")
	private String licenseFile;

	@Parameter(property="citation", required=false, defaultValue="${project.basedir}/CITATION.md")
	private String citation;

	@Parameter(property="funding", required=false, defaultValue="${project.basedir}/FUNDING.md")
	private String funding;

	@Parameter(property="releaseNotes", required=false, defaultValue="${project.basedir}/RELEASE-NOTES.md")
	private String releaseNotes;

	public CompileRascalDocumentation() {
		super("org.rascalmpl.shell.RascalTutorCompile", "tutor");
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

			libs.addAll(collectDependentArtifactLibraries(project));

			for (File lib : libs) {
				getLog().info("\tregistered library location: " + lib);
			}

			getLog().info("Paths have been configured.");

			extraParameters.putAll(Map.of(
				"license", licenseFile,
				"citation", citation,
				"funding", funding,
				"releaseNotes", releaseNotes

			));

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

}
