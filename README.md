Reusable, opinionated gradle build infrastructure
==

Plugin: ```buildinfra.buildoptions```
--

Build options are named gradle Property<String>
values, with values sourced dynamically from 
(in order):
* system property (-Dfoo=value),
* gradle property (-Pfoo=value),
* environment variable (foo=value ./gradlew ...)
* local, typically unversioned, ```.local-options.properties``` property file.

Typical usage in a build file:
```groovy
buildOptions {
  addOption("foo", "Option foo, no default value.")
  addOption("bar", "Option bar, with default value.", "baz")
}

...
Provider<String> bar = buildOptions["bar"]
Provider<String> foo = buildOptions["foo"]
```

Show all current option values for the project (compare
the output for both):

```shell
./gradlew buildOptions
./gradlew buildOptions -Pfoo=xyz -Dbar=abc
```

Plugin: ```buildinfra.testing-env```
--

Sets up certain defaults for Java tests. 

This plugin adds the following build options to Java projects and applies them to all ```Test```
tasks.

* ```tests.htmlReports```: Configures HTML report generation from tests. Default: ```false``` 
* ```tests.rerun```: Force re-running tests. Default: ```false```
* ```tests.jvmargs```: Additional arguments to pass directly to the forked test runner JVMs.
* ```tests.minheap```: Minimum heap size for test JVMs.
* ```tests.maxheap```: Minimum heap size for test JVMs.
* ```tests.jvms```: The number of forked test JVMs.
* ```tests.verbose```: Echo all stdout/stderr from tests to gradle console.
* ```tests.cwd.dir```: Current working directory for test JVMs (build-dir relative). Default: ```test-cwd```
* ```tests.tmp.dir```: Temporary directory for test JVMs (build-dir relative). Default: ```test-tmp```
