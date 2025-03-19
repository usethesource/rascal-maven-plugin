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
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Maven Goal for starting a rascal console for the current mvn project.
 */
@Mojo(name="console", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class RascalConsoleMojo extends AbstractRascalMojo
{
	public RascalConsoleMojo() {
		super("org.rascalmpl.shell.RascalShell","console");
	}

	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	public void execute() throws MojoExecutionException {
	    String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

        try {
            List<String> command = new LinkedList<String>();
            command.add(javaBin);

            System.getProperties().forEach((key, value) -> {
                command.add("-D" + key + "=" + value);
            });

            command.add("-cp");
            command.add(rascalRuntime.getPath());
            command.add("org.rascalmpl.shell.RascalShell");

            ProcessBuilder builder = new ProcessBuilder(command);
            Process process = builder.inheritIO().start();
            process.waitFor();
        } catch (IOException e) {
            getLog().error(e);
        } catch (InterruptedException e) {
            getLog().warn(e);
        }
	}
}
