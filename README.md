# rascal-maven-plugin

This MVN plugin runs the experimental Rascal checker (and later also the compiler).

### Functionality
* It loads a Rascal interpreter and then the source of the compiler into that interpreter.
* Then it executes the Rascal checker and prints the results on the commandline.
* Output files such as `.tpl` and `.class` files are written to the target folder
* The build fails if errors are detected. Warnings are only printed.

### Configuration

* `<bin>` configures where the `.tpl` and `.class` files are stored which are the result of compilation
* You can to provide it a (source) location of entire compiler to run using the `<boot>` parameter, this will become a library location after the MOJO is upgraded to use the bootstrapped compiler.
* The source path of the compiler is configured using the `<srcs>` configuration parameter
* The library path of the compiler is configured using the `<libs>` configuration parameter, but the MOJO will pick up all dependent artifacts (jars) which have `META-INF/RASCAL.MF` present in their root folder as well. Those jars will be searched for `.tpl` files and `.class` files which are produced by the Rascal compiler.

```
<build>
    <plugins>
      <plugin>
        <groupId>org.rascalmpl</groupId>
        <artifactId>rascal-maven-plugin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <configuration>
              <bin>${project.build.outputDirectory}</bin>
              <srcs>
                 <src>${project.basedir}/src</src>
              </srcs>
              <ignores>
                 <ignore>${project.basedir}/src/experimental</ignore>
              </ignores>
        </configuration>
        <executions>
          <execution>
            <id>it-compile</id>
            <phase>compile</phase>
            <goals>
              <goal>compile-rascal</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```
