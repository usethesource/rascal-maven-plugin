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

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.rascalmpl.exceptions.Throw;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.staticErrors.StaticError;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.library.util.PathConfig;
import org.rascalmpl.uri.ILogicalSourceLocationResolver;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.ValueFactoryFactory;

import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IValueFactory;
import io.usethesource.vallang.exceptions.FactTypeUseException;
import io.usethesource.vallang.io.StandardTextReader;

/**
 * Maven Goal for running local Rascal programs during the maven generate-source phase.
 *
 * When invoked it will make sure local Rascal programs are runnable and execute them.
 * The running Rascal program is assumed to have code generation as a (side) effect.
 */
@Mojo(name="generate-sources", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateSourcesUsingRascalMojo extends AbstractMojo
{
    private static final String UNEXPECTED_ERROR = "unexpected error during Rascal run";

    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;

    @Parameter(defaultValue="${project.build.directory}/generated", required=true)
    private String generated;

    @Parameter(property = "mainModule", required=true)
    private String mainModule;

    @Parameter(property = "mainFunction", required=true)
    private String mainFunction;

    @Parameter(property = "libs", required=false)
    private List<String> libs;

    @Parameter(property = "srcs", required=true)
    private List<String> srcs;

    private final  MojoRascalMonitor monitor = new MojoRascalMonitor(getLog(), false);

    private Evaluator makeEvaluator(PathConfig pcfg) throws URISyntaxException, FactTypeUseException, IOException {

		ISourceLocation[] searchPath = new ISourceLocation[libs.size() + srcs.size()];
		int spIndex = 0;
		for (String lib : libs) {
			searchPath[spIndex++] = MojoUtils.location(lib);
		}
        for (String src: srcs) {
			searchPath[spIndex++] = MojoUtils.location(src);
		}

        URIResolverRegistry.getInstance().registerLogical(new ILogicalSourceLocationResolver() {
            ISourceLocation root = URIUtil.createFileLocation(project.getBasedir().getAbsolutePath());

            @Override
            public String scheme() {
                return "project";
            }

            @Override
            public ISourceLocation resolve(ISourceLocation input) throws IOException {
                return URIUtil.getChildLocation(root, input.getPath());
            }

            @Override
            public String authority() {
                return project.getName();
            }
        });

		return MojoUtils.makeEvaluator(
			getLog(),
			monitor,
			System.err,
			System.out,
			searchPath,
			mainModule
		);
    }

    public void execute() throws MojoExecutionException {
        Evaluator eval = null;

        try {
            ISourceLocation binLoc = MojoUtils.location(generated);
            List<ISourceLocation> srcLocs = MojoUtils.locations(srcs);
            List<ISourceLocation> libLocs = MojoUtils.locations(libs);

            MojoUtils.collectDependentArtifactLibraries(project, libLocs);

            for (ISourceLocation lib : libLocs) {
                getLog().info("\tregistered library location: " + lib);
            }

            getLog().info("paths have been configured");

            PathConfig pcfg = new PathConfig(srcLocs, libLocs, binLoc);

            eval = makeEvaluator(pcfg);

            eval.call(monitor, mainFunction, pcfg.asConstructor());

            getLog().info(mainFunction + " is done.");

            return;
        } catch (URISyntaxException | IOException e) {
            throw new MojoExecutionException(UNEXPECTED_ERROR, e);
        } catch (Throw e) {
            getLog().error(e.getLocation() + ": " + e.getMessage());
            getLog().error(e.getTrace().toString());
            throw new MojoExecutionException(UNEXPECTED_ERROR, e);
        } catch (StaticError e) {
            if (eval != null) {
                getLog().error(e.getLocation() + ": " + e.getMessage());
                getLog().error(eval.getStackTrace().toString());
            }
            throw e;
        }
    }
}
