/*******************************************************************************
 * Copyright (c) 2019 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 *   * Davy Landman - davy.landman@swat.engineering -Swat.engineering
 */
package org.rascalmpl.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Maven Goal for Rascal packaging. This focuses currently on:
 *    1. copying source files into the target folder at a specific location, for later
 *      reference by debuggers and other IDE features
 *    2. rewriting URI references to source files in "binaries", such as .tpl files to point
 *      to the copied source files in the jar rather than source files of the current project.
 *
 */
@Mojo(name="package", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PackageRascalMojo extends AbstractRascalMojo
{
	@Parameter(defaultValue = "|mvn://${project.groupId}--${project.name}--${project.version}/|", property = "sourceLookup", required = true )
    private String sourceLookup;

	public PackageRascalMojo(String mainClass, String skipTag) {
		super("lang::rascalcore::package::Packager", "package", false, "", "");
		extraParameters.put("sourceLookup", sourceLookup);
	}
}
