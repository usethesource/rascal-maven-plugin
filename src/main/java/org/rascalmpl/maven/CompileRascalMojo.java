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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
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
	private List<File> parallelPreChecks;

	private List<File> modules;

	@Parameter(property = "modulesFromFile", defaultValue = "false")
	private boolean modulesFromFile;

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

	public CompileRascalMojo() {
		super("org.rascalmpl.shell.RascalCompile", "compile");

		getLog().info("Intializing compiler...");

		modules = new ArrayList<>();
		try {
			if (modulesFromFile) {
				getLog().info("modulesFromFile = true");
				var modulesFile = File.createTempFile("rascal-maven-modules", ".txt");
				try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(modulesFile)))) {
					for (var f : modules) {
						w.write(f.getAbsolutePath());
						w.newLine();
					}
				}
				modules = List.of(modulesFile);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void setExtraParameters() {
		try {
			extraParameters.put("modules", files(modulesFromFile ? modules : getTodoList(bin, srcs, ignores, "rsc", "tpl", "$")));
			extraParameters.put("modulesFromFile", Boolean.toString(modulesFromFile));
			extraParameters.put("parallel", Boolean.toString(parallel));
			extraParameters.put("parallelMax", Integer.toString(parallelMax));
			extraParameters.put("parallelPreChecks", files(parallelPreChecks));
			extraParameters.put("logPathConfig", Boolean.toString(logPathConfig));
			extraParameters.put("logImports", Boolean.toString(logImports));
			extraParameters.put("logWrittenFiles", Boolean.toString(logWrittenFiles));
			extraParameters.put("warnUnused", Boolean.toString(warnUnused));
			extraParameters.put("warnUnusedFormals", Boolean.toString(warnUnusedFormals));
			extraParameters.put("warnUnusedVariables", Boolean.toString(warnUnusedVariables));
			extraParameters.put("warnUnusedPatternFormals", Boolean.toString(warnUnusedPatternFormals));
			extraParameters.put("errorsAsWarnings", Boolean.toString(errorsAsWarnings));
			extraParameters.put("warningsAsErrors", Boolean.toString(warningsAsErrors));
		} catch (InclusionScanException | URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
}
