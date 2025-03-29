/*******************************************************************************
 * Copyright (c) 2020 NWO-I CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 */
package org.rascalmpl.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Maven Goal for running local Rascal programs during the maven generate-source phase.
 *
 * When invoked it will make sure local Rascal programs are runnable and execute them.
 * The running Rascal program is assumed to have code generation as a (side) effect.
 */
@Mojo(name="generate-sources", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateSourcesUsingRascalMojo extends AbstractRascalMojo
{
	@Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;

    @Parameter(property = "mainModule", required=true)
    private String mainModule = "GenerateSources";

	public GenerateSourcesUsingRascalMojo(String mainClass, String skipTag) {
		super("org.rascalmpl.shell.RascalShell", "generate");
		extraParameters.put("mainModule", mainModule);
	}
}
