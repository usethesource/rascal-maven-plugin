package org.rascalmpl.maven;

import org.apache.maven.plugin.logging.Log;
import org.rascalmpl.debug.IRascalMonitor;

import io.usethesource.vallang.ISourceLocation;

/**
 * This monitor is for batch mode. We can use the TerminalProgressBarMonitor for interactive mode.
 */
public class MojoRascalMonitor implements IRascalMonitor {
	private final Log log;
    private final boolean chatty;

	public MojoRascalMonitor(Log log, boolean chatty) {
		this.log = log;
		this.chatty = chatty;
	}

	@Override
	public void jobStart(String name, int workShare, int totalWork) {
		jobStep(name, "");
	}

	@Override
	public void jobStep(String name, String message, int workShare) {
		if (chatty) {
			synchronized (log) {
				log.info(name + ":" + message);
			}
	    }
	}

	@Override
	public int jobEnd(String name, boolean succeeded) {
		return 0;
	}

	@Override
	public boolean jobIsCanceled(String name) {
		return false;
	}

	@Override
	public void jobTodo(String name, int work) {
		// ignoring
	}

	@Override
	public void warning(String message, ISourceLocation src) {
		synchronized (log) {
			log.warn(src.toString() + ": " + message);
		}
	}

	@Override
	public void endAllJobs() {
		// Do nothing
	}
}
