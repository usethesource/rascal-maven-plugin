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

	@Override
	public void startJob(String name) {
	}

	@Override
	public void startJob(String name, int totalWork) {
		startJob(name);
	}

	@Override
	public void startJob(String name, int workShare, int totalWork) {
		startJob(name);
	}

	@Override
	public void event(String name) {
	    if (chatty) {
			synchronized (log) {
				log.info(name);
			}
	    }
	}

	@Override
	public void event(String name, int inc) {
		event(name);

	}

	@Override
	public void event(int inc) {

	}

	@Override
	public int endJob(boolean succeeded) {
		return 0;
	}

	@Override
	public boolean isCanceled() {
		return false;
	}

	@Override
	public void todo(int work) {

	}

	@Override
	public void warning(String message, ISourceLocation src) {
		synchronized (log) {
			log.warn(src.toString() + ": " + message);
		}
	}
}
