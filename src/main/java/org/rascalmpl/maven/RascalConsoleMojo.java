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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
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
public class RascalConsoleMojo extends AbstractMojo
{
	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	public void execute() throws MojoExecutionException {
	    String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

        StringBuilder command = new StringBuilder();
        command.append(javaBin);
        command.append(" -cp ");
        command.append(collectClasspath());
        command.append(" org.rascalmpl.shell.RascalShell");

        getLog().info(command);
	}
	
	private String collectClasspath() {
	    StringBuilder builder = new StringBuilder();
	    
        for (Object o : project.getArtifacts()) {
            Artifact a = (Artifact) o;
            File file = a.getFile().getAbsoluteFile();
            builder.append(":" + file.getAbsolutePath());
        }
        
        return builder.toString().substring(1);
    }

}
