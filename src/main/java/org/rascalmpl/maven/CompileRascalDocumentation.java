/*******************************************************************************
 * Copyright (c) 2022 CWI
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
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.rascalmpl.debug.IRascalMonitor;
import org.rascalmpl.exceptions.Throw;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.IEvaluator;
import org.rascalmpl.interpreter.result.Result;
import org.rascalmpl.library.util.PathConfig;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.uri.project.ProjectURIResolver;

import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.IList;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.IValue;
import io.usethesource.vallang.exceptions.FactTypeUseException;

/**
 * Maven Goal for Rascal Tutor Documentation compilation. The input is a list of
 * Rascal source folders, and course folders, and the output is for each module
 * a markdown file and for each markdown file in a source course an output markdown
 * file. The compiler also copies images from source to a target assets folder.
 * Also a list of errors and warnings is printed on stderr.
 *
 * Note that during the compilation of documentation, the Rascal interpreter is
 * used to execute code examples for inclusion in the docs. This uses the REPL interface.
 */
@Mojo(name="tutor", inheritByDefault=false, defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CompileRascalDocumentation extends AbstractMojo
{
	private static final String UNEXPECTED_ERROR = "unexpected error during Rascal compiler run";
	private static final String MAIN_COMPILER_MODULE = "lang::rascal::tutor::Compiler";
	private static final ISourceLocation[] MAIN_COMPILER_SEARCH_PATH;

	static {
		try {
			MAIN_COMPILER_SEARCH_PATH= new ISourceLocation[] {
				PathConfig.resolveProjectOnClasspath("rascal-tutor")
			};
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	@Parameter(property = "bin", required = true, defaultValue = "${project.build.outputDirectory}")
	private String bin;

	@Parameter(property = "generatedSources", required = true, defaultValue = "${project.build.directory}/generatedSources")
	private String generatedSources;

	@Parameter(property = "srcs", required = true )
	private List<String> srcs;

	@Parameter(property = "ignores", required = true )
	private List<String> ignores;

	@Parameter(property = "libs", required = false )
	private List<String> libs;

	@Parameter(property = "errorsAsWarnings", required = false, defaultValue = "false" )
	private boolean errorsAsWarnings;

	@Parameter(property = "warningsAsErrors", required = false, defaultValue = "false" )
	private boolean warningsAsErrors;

	@Parameter(property="enableStandardLibrary", required = false, defaultValue="true")
	private boolean enableStandardLibrary;

	@Parameter(property="isPackageCourse", required=false, defaultValue="true")
	private boolean isPackageCourse;

	@Parameter(property="packageName", required=true, defaultValue="${project.artifactId}")
	private String packageName;

	@Parameter(property="packageVersion", required=true, defaultValue="${project.version}")
	private String packageVersion;

	@Parameter(property="packageGroup", required=true, defaultValue="${project.groupId}")
	private String packageGroup;

	@Parameter(property="license", required=false, defaultValue="${project.basedir}/LICENSE.md")
	private String licenseFile;

	@Parameter(property="funding", required=false, defaultValue="${project.basedir}/FUNDING.md")
	private String funding;

	@Parameter(property="citation", required=false, defaultValue="${project.basedir}/CITATION.md")
	private String citation;

	@Parameter(property="releaseNotes", required=false, defaultValue="${project.basedir}/RELEASE-NOTES.md")
	private String releaseNotes;

	@Parameter(property="issues", required=false)
	private String issues;

	@Parameter(property="sources", required=false)
	private String sources;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	public void execute() throws MojoExecutionException {
		try {
			ISourceLocation binLoc = URIUtil.getChildLocation(MojoUtils.location(bin), "docs");
			ISourceLocation generatedSourcesLoc = URIUtil.getChildLocation(MojoUtils.location(generatedSources), "docs");
			List<ISourceLocation> srcLocs 		= MojoUtils.locations(srcs);
			List<ISourceLocation> libLocs 		= MojoUtils.locations(libs);
			List<ISourceLocation> ignoredLocs 	= MojoUtils.locations(ignores);

			if (System.getProperty("rascal.documentation.skip") != null
			    || System.getProperty("rascal.tutor.skip") != null) {
				getLog().info("Skipping Rascal Documentation compiler completely");
				return;
			}

			getLog().info("configuring paths");
			for (ISourceLocation src : srcLocs) {
				getLog().info("\tregistered source location: " + src);
			}

			getLog().info("checking if any files need compilation");

			// complete libraries with maven artifacts which include a META-INF/RASCAL.MF file
			MojoUtils.collectDependentArtifactLibraries(project, libLocs);

			for (ISourceLocation lib : libLocs) {
				getLog().info("\tregistered library location: " + lib);
			}

			PathConfig pcfg = new PathConfig(srcLocs, libLocs, binLoc, ignoredLocs, generatedSourcesLoc, Collections.emptyList());

			getLog().info("Paths have been configured: " + pcfg);

			URIResolverRegistry.getInstance().registerLogical(
				new ProjectURIResolver(
					MojoUtils.location(
						project.getBasedir().getCanonicalFile().toString()),
						project.getName()
					)
				);

			Evaluator eval = MojoUtils.makeEvaluator(getLog(), session, MAIN_COMPILER_SEARCH_PATH, MAIN_COMPILER_MODULE);
			IList messages = runCompiler(eval.getMonitor(), eval, pcfg);

			getLog().info("Tutor is done, reporting errors now.");
			handleMessages(pcfg, messages);
			getLog().info("Error reporting done");

			return;
		} catch (URISyntaxException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (IOException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (Throw e) {
		    getLog().error(e.getLocation() + ": " + e.getMessage());
		    getLog().error(e.getTrace().toString());
		    throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		}
	}

	private List<ISourceLocation> collectClasspath() throws URISyntaxException {
	    List<ISourceLocation> builder = new LinkedList<>();
		boolean dependsOnRascal = false;

		builder.add(MojoUtils.location(bin));

        if ("org.rascalmpl".equals(project.getGroupId()) && "rascal".equals(project.getArtifactId())){
            File r = new File(project.getBuild().getOutputDirectory());

			if (!enableStandardLibrary) {
            	builder.add(URIUtil.createFileLocation(r.getAbsolutePath()));
			}
			dependsOnRascal = true;
        }

        for (Object o : project.getArtifacts()) {
            Artifact a = (Artifact) o;
            File file = a.getFile().getAbsoluteFile();

			if ("org.rascalmpl".equals(a.getGroupId()) && "rascal".equals(a.getArtifactId())) {
				dependsOnRascal = true;
				if (enableStandardLibrary) {
					builder.add(URIUtil.createFileLocation(file.getAbsolutePath()));
				}
			}
			else {
				builder.add(URIUtil.createFileLocation(file.getAbsolutePath()));
			}
        }

		if (!dependsOnRascal) {
			getLog().info("Current project does not have a dependency on org.rascalmpl:rascal");
		}

        return builder;
    }

	private List<ISourceLocation> collectPluginClasspath() throws URISyntaxException {
	    List<ISourceLocation> builder = new LinkedList<>();

		builder.add(MojoUtils.location(IValue.class.getProtectionDomain().getCodeSource().getLocation().getPath()));
		builder.add(MojoUtils.location(Evaluator.class.getProtectionDomain().getCodeSource().getLocation().getPath()));


        return builder;
    }

	private IList runCompiler(IRascalMonitor monitor, IEvaluator<Result<IValue>> eval, PathConfig pcfg) throws URISyntaxException, IOException {
		try {
			IConstructor pc =  pcfg.asConstructor();
			pc = pc.asWithKeywordParameters().setParameter("isPackageCourse", eval.getValueFactory().bool(isPackageCourse));
			pc = pc.asWithKeywordParameters().setParameter("packageName", eval.getValueFactory().string(packageName));
			pc = pc.asWithKeywordParameters().setParameter("packageVersion", eval.getValueFactory().string(packageVersion));
			pc = pc.asWithKeywordParameters().setParameter("packageGroup", eval.getValueFactory().string(packageGroup));
			pc = pc.asWithKeywordParameters().setParameter("packageRoot", URIUtil.createFileLocation(project.getBasedir().getCanonicalPath()));

			if (sources != null) {
				pc = pc.asWithKeywordParameters().setParameter("sources", MojoUtils.location(sources));
			}

			if (issues != null) {
				pc = pc.asWithKeywordParameters().setParameter("issues", MojoUtils.location(issues));
			}

			pc = pc.asWithKeywordParameters().setParameter("license", MojoUtils.location(licenseFile));
			pc = pc.asWithKeywordParameters().setParameter("funding", MojoUtils.location(funding));
			pc = pc.asWithKeywordParameters().setParameter("citation", MojoUtils.location(citation));
			pc = pc.asWithKeywordParameters().setParameter("releaseNotes", MojoUtils.location(releaseNotes));

			eval.getErrorPrinter().println(pc);
			return (IList) eval.call(monitor, "compile", pc);
		}
		finally {
			eval.getErrorPrinter().flush();
			eval.getOutPrinter().flush();
		}
	}

	private void handleMessages(PathConfig pcfg, IList messages) throws MojoExecutionException {
		int maxLine = 0;
		int maxColumn = 0;
		boolean hasErrors = false;

		for (IValue error : messages) {
			ISourceLocation loc = (ISourceLocation) ((IConstructor) error).get("at");
			if(loc.hasLineColumn()) {
				maxLine = Math.max(loc.getBeginLine(), maxLine);
				maxColumn = Math.max(loc.getBeginColumn(), maxColumn);
			} else {
				getLog().error("loc without line/column: " + loc);
			}
		}

		int lineWidth = (int) Math.log10(maxLine + 1) + 1;
		int colWidth = (int) Math.log10(maxColumn + 1) + 1;

		for (IValue error : messages) {
			IConstructor msg = (IConstructor) error;
			String type = msg.getName();
			boolean isError = type.equals("error");
			boolean isWarning = type.equals("warning");

			hasErrors |= isError || (warningsAsErrors && isWarning);

			ISourceLocation loc = (ISourceLocation) msg.get("at");
			int col = 0;
			int line = 0;
			if(loc.hasLineColumn()) {
				col = loc.getBeginColumn();
				line = loc.getBeginLine();
			}

			String output
			= abbreviate(loc, pcfg)
			+ ":"
			+ String.format("%0" + lineWidth + "d", line)
			+ ":"
			+ String.format("%0" + colWidth + "d", col)
			+ ": "
			+ ((IString) msg.get("msg")).getValue();

			if (isError) {
				// align "[ERROR]" with "[WARNING]" by adding two spaces
				getLog().error("  " + output);
			}
			else if (isWarning) {
				getLog().warn(output);
			}
			else {
				// align "[INFO]" with "[WARNING]" by adding three spaces
				getLog().info("   " + output);
			}
		}

		if (hasErrors && !errorsAsWarnings) {
			getLog().error("More than zero errors!");
			throw new MojoExecutionException("Tutor compiler found compile-time errors");
		}

		return;
	}

	private static String abbreviate(ISourceLocation loc, PathConfig pcfg) {
		for (IValue src : pcfg.getSrcs()) {
			String path = ((ISourceLocation) src).getURI().getPath();

			if (loc.getPath().startsWith(path)) {
				return URIUtil.getLocationName((ISourceLocation) src) + "/" + loc.getPath().substring(path.length());
			}
		}

		return loc.getPath();
	}
}
