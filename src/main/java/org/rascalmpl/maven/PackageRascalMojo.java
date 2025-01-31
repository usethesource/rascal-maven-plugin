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

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.ValueFactoryFactory;
import io.usethesource.vallang.IList;
import io.usethesource.vallang.IListWriter;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.exceptions.FactTypeUseException;

/**
 * Maven Goal for Rascal packaging. This focuses currently on:
 *    1. copying source files into the target folder at a specific location, for later
 *      reference by debuggers and other IDE features
 *    2. rewriting URI references to source files in "binaries", such as .tpl files to point
 *      to the copied source files in the jar rather than source files of the current project.
 *
 */
@Mojo(name="package", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PackageRascalMojo extends AbstractMojo
{
    private static final String MAIN_PACKAGER_MODULE = "lang::rascalcore::package::Packager";
	private static final ISourceLocation[] MAIN_PACKAGER_SEARCH_PATH = new ISourceLocation[] {
		URIUtil.correctLocation("lib", "typepal", ""),
		URIUtil.correctLocation("lib", "rascal-core", "")
	};

	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
	private String bin;

	@Parameter(property = "srcs", required = true)
	private List<String> srcs;

	@Parameter(defaultValue = "|lib://${project.name}/|", property = "sourceLookup", required = true )
    private String sourceLookup;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
  	private MavenSession session;

	private Evaluator makeEvaluator() throws URISyntaxException, FactTypeUseException, IOException {
		return MojoUtils.makeEvaluator(
			getLog(),
			session,
			MAIN_PACKAGER_SEARCH_PATH,
			MAIN_PACKAGER_MODULE
		);
	}

	public void execute() throws MojoExecutionException {
		try {
		    Evaluator eval = makeEvaluator();

			ISourceLocation binLoc = MojoUtils.location(bin);
			ISourceLocation sourceLookupLoc = MojoUtils.location(sourceLookup);
			IList srcLocs = locations(srcs);

			eval.call("package", srcLocs, binLoc, sourceLookupLoc);

			getLog().info("packager is done.");
		} catch (URISyntaxException | IOException e) {
			throw new MojoExecutionException("unexpected error during Rascal compiler run", e);
		}
	}

	private IList locations(List<String> files) {
		IListWriter result = ValueFactoryFactory.getValueFactory().listWriter();
		result.appendAll(MojoUtils.locations(files));
		return result.done();
	}

}
