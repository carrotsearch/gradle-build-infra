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
* versioned ```.options.properties"``` property file.
* local, typically unversioned, ```.options.local.properties``` property file.

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

* ```tests.cwd.dir```: Current working directory for test JVMs. Default: ```test-cwd```
* ```tests.htmlReports```: Configures HTML report generation from tests. Default: ```false``` 
* ```tests.jvmargs```: Additional arguments to pass directly to the forked test runner JVMs.
* ```tests.jvms```: The number of forked test JVMs.
* ```tests.maxheap```: Minimum heap size for test JVMs.
* ```tests.minheap```: Minimum heap size for test JVMs.
* ```tests.rerun```: Force re-running tests. Default: ```false```
* ```tests.tmp.dir```: Temporary directory for test JVMs. Default: ```test-tmp```
* ```tests.verbose```: Echo all stdout/stderr from tests to gradle console.

The following properties can be used to configure the randomizedtesting
unit test randomization framework.

* ```tests.asserts```: The desired assertions status for RequireAssertionsRule (true/false).
* ```tests.filter```: Apply test group filtering using Boolean expressions.
* ```tests.iters```: Repeats randomized tests the provided number of times.
* ```tests.seed```: Root seed for the randomizedtesting framework. Default: randomly computed.
* ```tests.stackfiltering```: Enable or disable stack filtering.
* ```tests.timeoutSuite```: Test suite timeout (in millis).
* ```tests.timeout```: Test timeout (in millis).

Plugin: ```buildinfra.conventions.reproducible-builds```
--

Sets up sane defaults for all archive tasks. These include predictable file order,
no timestamps and constant (unix) file permissions.
