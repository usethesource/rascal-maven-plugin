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
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
public class GenerateSourcesUsingRascalMojo extends AbstractMojo
{
    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;

    @Parameter(property = "mainModule", required=true)
    private String mainModule;

    public void execute() throws MojoExecutionException {
		if (System.getProperty("rascal.generate.skip") != null) {
			return;
		}

	    String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
		getLog().info("Using " + javaBin + " as java process for nested jvm call");

        List<String> command = new LinkedList<String>();
        command.add(javaBin);
        command.add("-cp");
        command.add(collectClasspath());
        command.add("org.rascalmpl.shell.RascalShell");
        command.add(mainModule);

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(project.getBasedir());
            Process process = builder.inheritIO().start();
            process.waitFor();
        } 
        catch (IOException e) {
            getLog().error(e);
        } 
        catch (InterruptedException e) {
            getLog().warn(e);
        }
        finally {}
    }

    private String collectClasspath() {
	    StringBuilder builder = new StringBuilder();

        for (Artifact a : project.getArtifacts()) {
            File file = a.getFile().getAbsoluteFile();
			getLog().debug("Adding " + file + " to classpath");
            builder.append(File.pathSeparator + file.getAbsolutePath());
        }

        return builder.toString().substring(1);
    }
}
