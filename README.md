# rascal-maven-plugin

This MVN plugin runs the experimental Rascal checker and compiler.

* You have to provide it a source location of the compiler to run using the `<boot>` parameter. 
* It loads a Rascal interpret and then the source of the compiler into that interpreter.
* Then it executes the Rascal checker and prints the results on the commandline. 
* The build fails if errors are detected. Warnings are only printed.

```
<build>
    <plugins>
      <plugin>
        <groupId>org.rascalmpl</groupId>
        <artifactId>rascal-maven-plugin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <configuration>
              <boot>/Users/jurgenv/git/rascal-core/src/org/rascalmpl/core/library</boot>
              <bin>${project.build.outputDirectory}</bin>
              <srcs>
                 <src>${project.baseDir}/src</src>
              </srcs>
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
