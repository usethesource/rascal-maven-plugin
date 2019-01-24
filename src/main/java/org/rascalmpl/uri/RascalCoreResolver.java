package org.rascalmpl.uri;

import org.rascalmpl.uri.libraries.ClassResourceInput;

public class RascalCoreResolver extends ClassResourceInput {
	public RascalCoreResolver() {
		super("rascalcore", org.rascalmpl.core.values.ValueFactoryFactory.class, "src/org/rascalmpl/core/library");
	}
}
