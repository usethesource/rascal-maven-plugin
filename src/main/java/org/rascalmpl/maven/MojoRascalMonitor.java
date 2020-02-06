package org.rascalmpl.maven;

import org.apache.maven.plugin.logging.Log;
import org.rascalmpl.debug.IRascalMonitor;

import io.usethesource.vallang.ISourceLocation;

public class MojoRascalMonitor implements IRascalMonitor {
	private final Log log;

	public MojoRascalMonitor(Log log) {
		this.log = log;
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
		log.warn(src.toString() + ": " + message);
	}
}