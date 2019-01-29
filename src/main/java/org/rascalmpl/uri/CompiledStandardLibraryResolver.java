package org.rascalmpl.uri;

import org.rascalmpl.library.Prelude;
import org.rascalmpl.uri.libraries.ClassResourceInput;

public class CompiledStandardLibraryResolver extends ClassResourceInput {
	public CompiledStandardLibraryResolver() {
		super("stdlib", Prelude.class, "/");
	}
}
