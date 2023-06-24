/*******************************************************************************
 * Copyright (c) 2021 CWI All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors: * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI * Davy Landman - davy.landman@swat.engineering -
 * Swat.engineering
 */
package org.rascalmpl.maven;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.debug.IRascalMonitor;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.uri.URIUtil;
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
		eval.addRascalSearchPath(loc);
	}

	static Evaluator makeEvaluator(Log log, IRascalMonitor monitor, OutputStream err, OutputStream out, ISourceLocation[] searchPath,  String... importedModules) throws URISyntaxException, FactTypeUseException, IOException {
		safeLog(log, l -> l.info("start loading the compiler"));
		GlobalEnvironment heap = new GlobalEnvironment();
		Evaluator eval = new Evaluator(ValueFactoryFactory.getValueFactory(), new ByteArrayInputStream(new byte[0]), err, out, new ModuleEnvironment("***MVN Rascal Compiler***", heap), heap);
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

		safeLog(log, l -> l.info("done loading the compiler"));

		return eval;
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

	static void collectDependentArtifactLibraries(MavenProject project, List<ISourceLocation> libLocs) throws URISyntaxException, IOException {
		RascalManifest mf = new RascalManifest();
		Set<String> projects = libLocs.stream().map(mf::getProjectName).collect(Collectors.toSet());
		 URIResolverRegistry reg = URIResolverRegistry.getInstance();

		for (Object o : project.getArtifacts()) {
			Artifact a = (Artifact) o;
			File file = a.getFile().getAbsoluteFile();
			ISourceLocation jarLoc = RascalManifest.jarify(MojoUtils.location(file.toString()));

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
}
