# rascal-maven-plugin

This MVN plugin runs the experimental Rascal checker (and later also the compiler). Next to this 
core functionality we have plugins for running the rascal tutor compiler, starting a rascal REPL console, executing arbitrary rascal code from the pom file and packaging rascal binaries in relocatable jar files. 

### Functionality

This plugin is a shallow wrapper for selected rascal commandline tools 
that originate from the usethesource/rascal project:

* org.rascalmpl.shell.RascalCheck
* org.rascalmpl.shell.RascalCompile
* org.rascalmpl.shell.TutorCompile
* org.rascalmpl.shell.RascalShell

Each of these are configured via PathConfig constructors
which the current plugin composes from elements in the pom.xm

### Documentation

More documentation can be found here: http://www.rascal-mpl.org