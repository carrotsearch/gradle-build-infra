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

This plugin adds the following build options to Java projects:
* ```tests.htmlReports```: Configures HTML report generation from tests. Default: ```false``` 
