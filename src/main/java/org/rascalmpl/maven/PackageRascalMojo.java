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
import java.io.StringReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.ValueFactoryFactory;

import io.usethesource.vallang.IList;
import io.usethesource.vallang.IListWriter;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IValueFactory;
import io.usethesource.vallang.exceptions.FactTypeUseException;
import io.usethesource.vallang.io.StandardTextReader;

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
    private static final String UNEXPECTED_ERROR = "unexpected error during Rascal compiler run";
    private static final String MAIN_PACKAGER_MODULE = "lang::rascalcore::package::Packager";
    private static final String INFO_PREFIX_MODULE_PATH = "\trascal module path addition: ";
    
	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
	private String bin;

	@Parameter(property = "srcs", required = true)
	private List<String> srcs;
	
	@Parameter(defaultValue = "|lib://${project.name}/|", property = "sourceLookup", required = true )
    private String sourceLookup;
    
	private Evaluator makeEvaluator() throws URISyntaxException, FactTypeUseException, IOException {
		getLog().info("start loading the packager");
		GlobalEnvironment heap = new GlobalEnvironment();
		Evaluator eval = new Evaluator(ValueFactoryFactory.getValueFactory(), System.in, System.err, System.out, new ModuleEnvironment("***MVN Rascal Packager***", heap), heap);

		URL vallangJarFile = IValueFactory.class.getProtectionDomain().getCodeSource().getLocation();
		eval.getConfiguration().setRascalJavaClassPathProperty(new File(vallangJarFile.toURI()).toString());

		MojoRascalMonitor monitor = new MojoRascalMonitor(getLog(), false);
        eval.setMonitor(monitor);

		getLog().info(INFO_PREFIX_MODULE_PATH + "|lib://typepal/|");
        eval.addRascalSearchPath(URIUtil.correctLocation("lib", "typepal", ""));

		getLog().info(INFO_PREFIX_MODULE_PATH + "|lib://rascal-core/|");
		eval.addRascalSearchPath(URIUtil.correctLocation("lib", "rascal-core", ""));
		
		getLog().info(INFO_PREFIX_MODULE_PATH + "|std:///|");
		eval.addRascalSearchPath(URIUtil.rootLocation("std"));

		getLog().info("\timporting " + MAIN_PACKAGER_MODULE);
		eval.doImport(monitor, MAIN_PACKAGER_MODULE);

		getLog().info("done loading the packager");

		return eval;
	}

	public void execute() throws MojoExecutionException {
		try {
		    Evaluator eval = makeEvaluator();
		    
			ISourceLocation binLoc = location(bin);
			ISourceLocation sourceLookupLoc = location(sourceLookup);
			IList srcLocs = locations(srcs);
			
			eval.call(new MojoRascalMonitor(getLog(), true), "package", srcLocs, binLoc, sourceLookupLoc);

			getLog().info("packager is done.");
			
			return;
		} catch (URISyntaxException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (IOException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} 
	}

	private IList locations(List<String> files) throws URISyntaxException, FactTypeUseException, IOException {
		IListWriter result = ValueFactoryFactory.getValueFactory().listWriter();

		for (String f : files) {
			result.append(location(f));
		}

		return result.done();
	}

	private ISourceLocation location(String file) throws URISyntaxException, FactTypeUseException, IOException {
		if (file.startsWith("|") && file.endsWith("|")) {
			return (ISourceLocation) new StandardTextReader().read(ValueFactoryFactory.getValueFactory(), new StringReader(file));
		}
		else {
			return URIUtil.createFileLocation(file);
		}
	}
}
