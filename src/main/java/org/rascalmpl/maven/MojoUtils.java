/*******************************************************************************
 * Copyright (c) 2021 CWI All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors: * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI * Davy Landman - davy.landman@swat.engineering -
 * Swat.engineering
 */
package org.rascalmpl.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.OSUtils;
import org.jline.utils.InfoCmp.Capability;
import org.rascalmpl.debug.IRascalMonitor;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.library.util.PathConfig;
import org.rascalmpl.library.util.SemVer;
import org.rascalmpl.repl.streams.RedErrorWriter;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.uri.jar.JarURIResolver;
import org.rascalmpl.values.ValueFactoryFactory;

import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IValueFactory;
import io.usethesource.vallang.exceptions.FactTypeUseException;
import io.usethesource.vallang.io.StandardTextReader;

public class MojoUtils {

	private static void safeLog(Log log, Consumer<Log> action) {
		synchronized (log) {
			action.accept(log);
		}
	}

	private static void addSearchPath(Log log, Evaluator eval, ISourceLocation loc) {
		safeLog(log, l -> l.info("\trascal module path addition: " + loc));
		eval.addRascalSearchPath(JarURIResolver.jarify(loc));
	}

	static IRascalMonitor buildMonitor(MavenSession session, Log log) {
		return session.getRequest().isInteractiveMode() // batch mode enabled or not
			? getTerminalProgressBarInstance()
			: new MojoRascalMonitor(log, false);
	}

	static ImmutableTriple<IRascalMonitor, PrintWriter, PrintWriter> calculateMonitor(Log log, MavenSession session) {
		var monitor = buildMonitor(session, log);
		var out = (monitor instanceof PrintWriter) ? (PrintWriter)monitor : new PrintWriter(System.out);
		var err = (monitor instanceof PrintWriter) ? new PrintWriter(new RedErrorWriter(out), true) : new PrintWriter(System.err, true);
		return ImmutableTriple.of(monitor, err, out);
	}

	static Evaluator makeEvaluator(Log log, MavenSession session, ISourceLocation[] searchPath,  String... importedModules) throws URISyntaxException, FactTypeUseException, IOException {
		var streams = calculateMonitor(log, session);
		return makeEvaluator(log, session, streams.left, streams.middle, streams.right, searchPath, importedModules);
	}

	static Evaluator makeEvaluator(Log log, MavenSession session, IRascalMonitor monitor, PrintWriter out, PrintWriter err, ISourceLocation[] searchPath,  String... importedModules) throws URISyntaxException, FactTypeUseException, IOException {
		safeLog(log, l -> l.info("Start loading the compiler..."));
		GlobalEnvironment heap = new GlobalEnvironment();

		Evaluator eval = new Evaluator(ValueFactoryFactory.getValueFactory(), Reader.nullReader(), err, out, monitor, new ModuleEnvironment("***MVN Rascal Compiler***", heap), heap);
		eval.getConfiguration().setRascalJavaClassPathProperty(toClassPath(
			ValueFactoryFactory.class, // rascal jar
			IValueFactory.class // vallang jar
		));

		eval.setMonitor(monitor);

		for (ISourceLocation sp: searchPath) {
			addSearchPath(log, eval, sp);
		}
		addSearchPath(log, eval, URIUtil.rootLocation("std"));

		for (String importModule:  importedModules) {
			safeLog(log, l -> l.info("\timporting " + importModule));
			eval.doImport(monitor, importModule);
		}

		safeLog(log, l -> l.info("Done loading the compiler."));

		return eval;
	}

	private static class MonitorInstanceHolder {
		static IRascalMonitor monitor;
		static {
			try {
				var terminal = TerminalBuilder.builder();
				if (OSUtils.IS_WINDOWS) {
					terminal.encoding(StandardCharsets.UTF_8);
				}
				monitor = IRascalMonitor.buildConsoleMonitor(terminal.build(), false);
			} catch (IOException e) {
				throw new IllegalStateException("Could not build terminal", e);
			}
		}
	}

	private static IRascalMonitor getTerminalProgressBarInstance() {
		return MonitorInstanceHolder.monitor;
	}

	private static String toClassPath(Class<?>... clazz) {
		return Arrays.stream(clazz)
			.map(c -> c.getProtectionDomain().getCodeSource().getLocation())
			.map(u -> {
				try {
					return u.toURI();
				} catch (URISyntaxException e) {
					throw new RuntimeException(e);
				}
			})
			.map(File::new)
			.map(File::toString)
			.collect(Collectors.joining(System.getProperty("path.separator")));
	}

    static ISourceLocation location(String file) {
    	if (file.trim().startsWith("|") && file.trim().endsWith("|")) {
    		try {
    			return (ISourceLocation) new StandardTextReader().read(ValueFactoryFactory.getValueFactory(), new StringReader(file));
    		} catch (IOException e) {
    		    throw new RuntimeException(e);
    		}
    	}
    	else {
    		try {
    			return URIUtil.createFileLocation(file);
    		} catch (URISyntaxException e) {
    			throw new RuntimeException(e);
    		}
    	}
    }

    static List<ISourceLocation> locations(List<String> files) {
    	return files.stream().
			map(MojoUtils::location)
			.collect(Collectors.toCollection(ArrayList::new));
    }

	/**
	 * Finds all the jar files for the dependencies in this pom.xml
	 * @param project
	 * @param libLocs
	 * @throws URISyntaxException
	 * @throws IOException
	 */
	static void collectDependentArtifactLibraries(MavenProject project, List<ISourceLocation> libLocs) throws URISyntaxException, IOException {
		RascalManifest mf = new RascalManifest();
		Set<String> projects = libLocs.stream().map(mf::getProjectName).collect(Collectors.toSet());
		 URIResolverRegistry reg = URIResolverRegistry.getInstance();

		for (Object o : project.getArtifacts()) {
			Artifact a = (Artifact) o;
			File file = a.getFile().getAbsoluteFile();
			ISourceLocation jarLoc = JarURIResolver.jarify(MojoUtils.location(file.toString()));

			if (reg.exists(URIUtil.getChildLocation(jarLoc, "META-INF/RASCAL.MF"))) {
				final String projectName = mf.getProjectName(jarLoc);

				// only add a library if it is not already on the lib path
				if (!projects.contains(projectName)) {
					libLocs.add(jarLoc);
					projects.add(projectName);
				}
			}
		}
	}

	/**
	 * Finds the rascal.jar file that the pom.xml depends on, or if it does not exist the rascal.jar
	 * this maven plugin depends on. Also when the version of the dependency is younger than 0.41.0-RC16
	 * the resolution defaults to what the maven-plugin depends on itself. Before that version there
	 * were no CLI classes to run.
	 */
	static File detectedDependentRascalArtifact(Log log, MavenProject project) {
		for (Object o : project.getArtifacts()) {
			Artifact a = (Artifact) o;

			if (a.getArtifactId().equals("org.rascalmpl:rascal")) {
				File file = a.getFile().getAbsoluteFile();

				if (new SemVer(a.getVersion()).greaterEqualVersion(new SemVer("0.41.0-RC16"))) {
					return file;
				}
				else {
					log.warn("Rascal version in pom.xml dependency is too old for this Rascal maven plugin. >= 0.41.0 expected.");
				}
			}
		}

		try {
			// if we don't have a proper dependency on org.rascalmpl:rascal, we go with _our_ dependency on rascal:
			return new File(PathConfig.resolveCurrentRascalRuntimeJar().getPath());
		}
		catch (IOException e) {
			log.error(e.getMessage());
			// having no rascal runtime is a fatal error
			System.exit(1);
			return null;
		}
	}
}
