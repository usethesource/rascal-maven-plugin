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
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.library.util.PathConfig;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.ValueFactoryFactory;

import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.IList;
import io.usethesource.vallang.IListWriter;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.IValue;
import io.usethesource.vallang.exceptions.FactTypeUseException;

/**
 * Maven Goal for Rascal compilation. The input is a list of
 * Rascal source folders, the output is for each module in the source tree:
 * a .tpl file and possibly/optionally a .class file. Also a list of errors
 * and warnings is printed on stderr. 
 * 
 * TODO The Mojo currently uses the Rascal interpreter to run 
 * what is currently finished of the compiler and type-checker. Since the compiler 
 * is not finished yet, the behavior of this mojo is highly experimental and fluid. 
 * 
 * TODO When the compiler will be bootstrapped, this mojo will run a generated
 * compiler instead of the source code of the compiler inside the Rascal interpreter.
 * 
 * TODO This Mojo always requires a 'boot' parameter which points to the file location of the
 * source of the compiler. After the bootstrap, this parameter will become optional.
 * 
 * @phase compile
 */
@Mojo(name="compile-rascal", defaultPhase = LifecyclePhase.COMPILE )
public class CompileRascalMojo extends AbstractMojo
{
    private static final String MAIN_COMPILER_MODULE = "lang::rascalcore::check::Checker";

	@Parameter(defaultValue = "${project.baseDir}/src", property = "boot", required = true )
    private File boot;
    
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
    private File bin;
    
    @Parameter(property = "srcs", required = true )
    private List<File> srcs;
    
    @Parameter(property = "libs", required = true )
    private List<File> libs;

    private final PrintWriter err = new PrintWriter(System.err);
    private final PrintWriter out = new PrintWriter(System.out);
    
	private Evaluator makeEvaluator(File boot) throws URISyntaxException {
		getLog().info("start loading the compiler");
		GlobalEnvironment heap = new GlobalEnvironment();
    	Evaluator eval = new Evaluator(ValueFactoryFactory.getValueFactory(), err, out, new ModuleEnvironment("***MVN Rascal Compiler***", heap), heap);
    	eval.addRascalSearchPath(URIUtil.createFromURI(boot.toURI().toString()));
    	eval.doImport(null, MAIN_COMPILER_MODULE);
    	getLog().info("done loading the compiler");
    	return eval;
	}
    
    public void execute() throws MojoExecutionException {
    	try {
			Evaluator eval = makeEvaluator(boot);
			ISourceLocation bootLoc = location(boot);
			ISourceLocation binLoc = location(bin);
			List<ISourceLocation> srcLocs = locations(srcs);
			List<ISourceLocation> libLocs = locations(libs);
			
			PathConfig pcfg = new PathConfig(srcLocs, libLocs, binLoc, bootLoc);
			IConstructor config = pcfg.asConstructor();
			IListWriter files = eval.getValueFactory().listWriter();
			findAllRascalFiles(srcs, files);
			
			IList messages = (IList) eval.call("check", files.done(), config);
			handleMessages(pcfg, messages);
		} catch (URISyntaxException e) {
			throw new MojoExecutionException("compiler parameter file issue", e);
		} catch (IOException e) {
			getLog().error(e);
		}
    }

	private void findAllRascalFiles(List<File> todo, IListWriter result) throws FactTypeUseException, URISyntaxException {
		for (File f : todo) {
			if (f.isDirectory()) {
				findAllRascalFiles(Arrays.asList(f.listFiles()), result);
			}
			else if (f.getName().endsWith(".rsc")) {
				result.insert(location(f));
			}
		}
	}
	
	private void handleMessages(PathConfig pcfg, IList messages) {
	    int maxLine = 0;
	    int maxColumn = 0;

	    for (IValue val : messages) {
	        ISourceLocation loc = (ISourceLocation) ((IConstructor) val).get("at");
	        maxLine = Math.max(loc.getBeginLine(), maxLine);
	        maxColumn = Math.max(loc.getBeginColumn(), maxColumn);
	    }


	    int lineWidth = (int) Math.log10(maxLine + 1) + 1;
	    int colWidth = (int) Math.log10(maxColumn + 1) + 1;

	    for (IValue val : messages) {
	    	IConstructor msg = (IConstructor) val;
	    	String type = msg.getName();
			boolean isError = type.equals("error");
	    	boolean isWarning = type.equals("warning");
	    	
	    	ISourceLocation loc = (ISourceLocation) msg.get("at");
	    	int col = loc.getBeginColumn();
	    	int line = loc.getBeginLine();

	    	String output 
	    	= type + "@" + abbreviate(loc, pcfg) 
	    	+ ":" 
	    	+ String.format("%0" + lineWidth + "d", line)
	    	+ ":"
	    	+ String.format("%0" + colWidth + "d", col)
	    	+ ": "
	    	+ ((IString) msg.get("msg")).getValue();
	    	
	    	if (isError) {
	    		getLog().error(output);
	    	}
	    	else if (isWarning) {
	    		getLog().warn(output);
	    	}
	    	else {
	    		getLog().info(output);
	    	}
	    }

	    return;
	}

	private static String abbreviate(ISourceLocation loc, PathConfig pcfg) {
        for (IValue src : pcfg.getSrcs()) {
            String path = ((ISourceLocation) src).getURI().getPath();
            
            if (loc.getURI().getPath().startsWith(path)) {
                return loc.getURI().getPath().substring(path.length()); 
            }
        }
        
        return loc.getURI().getPath();
    }

	private List<ISourceLocation> locations(List<File> files) throws URISyntaxException {
		List<ISourceLocation> result = new ArrayList<ISourceLocation>(files.size());
		
		for (File f : files) {
			result.add(location(f));
		}
		
		return result;
	}

	private ISourceLocation location(File file) throws URISyntaxException {
		return URIUtil.createFromURI(file.toURI().toString());
	}
}
