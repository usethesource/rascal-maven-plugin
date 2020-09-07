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
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.control_exceptions.Throw;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.staticErrors.StaticError;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.library.util.PathConfig;
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
    private static final String INFO_PREFIX_MODULE_PATH = "\trascal module path addition: ";
    private static final URIResolverRegistry reg = URIResolverRegistry.getInstance();
    
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
 
    private MojoRascalMonitor monitor;

    private Evaluator makeEvaluator(PathConfig pcfg) throws URISyntaxException, FactTypeUseException, IOException {
        getLog().info("start loading the Rascal interpreter");
        GlobalEnvironment heap = new GlobalEnvironment();
        Evaluator eval = new Evaluator(ValueFactoryFactory.getValueFactory(), System.in, System.err, System.out, new ModuleEnvironment("***MVN Rascal Generate Sources***", heap), heap);

        URL vallangJarFile = IValueFactory.class.getProtectionDomain().getCodeSource().getLocation();
        eval.getConfiguration().setRascalJavaClassPathProperty(new File(vallangJarFile.toURI()).toString());

        monitor = new MojoRascalMonitor(getLog(), false);
        eval.setMonitor(monitor);

        getLog().info(INFO_PREFIX_MODULE_PATH + "|std:///|");
        eval.addRascalSearchPath(URIUtil.rootLocation("std"));

        for (String lib : libs) {
            getLog().info("module search path addition: " + lib);
            eval.addRascalSearchPath(location(lib));
        }

        for (String src: srcs) {
            getLog().info("module search path addition: " + src);
            eval.addRascalSearchPath(location(src));
        }
        
        getLog().info("\timporting " + mainModule);
        eval.doImport(monitor, mainModule);

        getLog().info("\tdone loading " + mainModule);

        return eval;
    }

    public void execute() throws MojoExecutionException {
        Evaluator eval = null;
        
        try {
            ISourceLocation binLoc = location(generated);
            List<ISourceLocation> srcLocs = locations(srcs);
            List<ISourceLocation> libLocs = locations(libs);
            
            collectDependentArtifactLibraries(libLocs);
            
            for (ISourceLocation lib : libLocs) {
                getLog().info("\tregistered library location: " + lib);
            }
            
            getLog().info("paths have been configured");
            
            PathConfig pcfg = new PathConfig(srcLocs, libLocs, binLoc);

            eval = makeEvaluator(pcfg);

            eval.call(monitor, mainFunction, pcfg.asConstructor());

            getLog().info(mainFunction + " is done.");
            
            return;
        } catch (URISyntaxException e) {
            throw new MojoExecutionException(UNEXPECTED_ERROR, e);
        } catch (IOException e) {
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

    private void collectDependentArtifactLibraries(List<ISourceLocation> libLocs) throws URISyntaxException, IOException {
        RascalManifest mf = new RascalManifest();
        Set<String> projects = libLocs.stream().map(l -> mf.getProjectName(l)).collect(Collectors.toSet());
        
        for (Object o : project.getArtifacts()) {
            Artifact a = (Artifact) o;
            File file = a.getFile().getAbsoluteFile();
            ISourceLocation jarLoc = RascalManifest.jarify(location(file.toString()));
            
            if (reg.exists(URIUtil.getChildLocation(jarLoc, "META-INF/RASCAL.MF"))) {
                String projectName = mf.getProjectName(jarLoc);
                
                // only add a library if it is not already on the lib path
                if (!projects.contains(projectName)) {
                    libLocs.add(jarLoc);
                    projects.add(projectName);
                }
            }
        }
    }

    private List<ISourceLocation> locations(List<String> files) throws URISyntaxException, FactTypeUseException, IOException {
        List<ISourceLocation> result = new ArrayList<ISourceLocation>(files.size());

        for (String f : files) {
            result.add(location(f));
        }

        return result;
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
