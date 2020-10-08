package org.rascalmpl.maven;

import org.apache.maven.plugin.logging.Log;
import org.rascalmpl.debug.IRascalMonitor;

import io.usethesource.vallang.ISourceLocation;

public class MojoRascalMonitor implements IRascalMonitor {
	private final Log log;
    private final boolean chatty;

	public MojoRascalMonitor(Log log, boolean chatty) {
		this.log = log;
		this.chatty = chatty;
	}

	public void startJob(String name) {
	}

	public void startJob(String name, int totalWork) {
		startJob(name);
	}

	public void startJob(String name, int workShare, int totalWork) {
		startJob(name);
	}

	public void event(String name) {
	    if (chatty) {
			synchronized (log) {
				log.info(name);
			}
	    }
	}

	public void event(String name, int inc) {
		event(name);

	}

	public void event(int inc) {

	}

	public int endJob(boolean succeeded) {
		return 0;
	}

	public boolean isCanceled() {
		return false;
	}

	public void todo(int work) {

	}

	public void warning(String message, ISourceLocation src) {
		synchronized (log) {
			log.warn(src.toString() + ": " + message);
		}
	}
}