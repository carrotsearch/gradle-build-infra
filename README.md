Reusable, opinionated gradle build infrastructure
==

This plugin configures a number of build aspects the way the author likes it. These
aspects include source formatting, dependency management, build options 
and other build scaffolding utilities.

Please take and use whatever you like. Contributions are welcome but if you need
extensive changes, please fork and tweak to your liking.

The following chapters describe sub-plugins that are applied to the build.

Plugin: ```com.carrotsearch.gradle.buildinfra``` (this plugin)
--

Applies all sub-plugins and exposes the following extension on the root project:
```groovy
buildinfra {
    // true, if the build runs within IntellIJ Idea environment.
    Property<Boolean> intelliJIdea;

    // access to ExecOperations for tasks that need it during execution phase.
    ExecOperations execOps;
}
```

Plugin: ```com.carrotsearch.gradle.buildinfra.environment.GitInfoPlugin```
--

Exposes the following extension on the root project:
```groovy
gitinfo {
    MapProperty<String, String> gitinfo;
}
```

the ```gitinfo``` object contains information about the current repository's state,
with the following defaults:
```
"git.commit": "unknown"
"git.commit-short": "unknown"
"git.clean": "false"
"git.changed-files": "not a checkout?"
"git.error-log": ""
```


Plugin: ```com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin```
--

Includes the ```com.carrotsearch.gradle.opts``` plugin transitively 
(https://github.com/carrotsearch/gradle-opts-plugin).

Adds the infrastructure for "build options". Build options are key-value pairs 
(gradle Provider<String> types), with values sourced dynamically from (in order):
* system property (-Dfoo=value),
* gradle property (-Pfoo=value),
* environment variable (foo=value ./gradlew ...)
* a local, typically *not versioned*, root-project relative, ```build-options.local.properties``` property file,
* a versioned root project-relative ```build-options.properties"``` property file.

Typical usage in a build file:
```groovy
buildOptions {
  addOption("foo", "String option foo, no default value.")
  addOption("bar", "String option bar, with default value.", "baz")
}
// or:
Provider<String> bazOption = buildOptions.addOption("baz", "Option baz.")

// retrieves value provider for the option's value.
{
  Provider<String> bar = buildOptions["bar"]
  Provider<String> foo = buildOptions["foo"]
}

// non-string options are also possible.
{
  Provider<Boolean> boolOpt = buildOptions.addBooleanOption("boolOpt", "Boolean option.", true)
  Provider<Integer> intOpt = buildOptions.addIntOption("intOpt", "integer option.", 42)
}
```

Show all current option values for the project (compare
the output for both):

```shell
./gradlew buildOptions
./gradlew buildOptions -Pfoo=xyz -Dbar=abc
```

Other plugins in buildinfra add their configurable settings as build options.

Plugin: ```com.carrotsearch.gradle.buildinfra.testing.TestingEnvPlugin```
--

Sets up convenient defaults for ```Test``` tasks, with particular focus
on setting up [randomizedtesting](https://github.com/randomizedtesting/randomizedtesting) 
tests.

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
* ```tests.verbose```: Echo all stdout/stderr from tests immediately to gradle console. 
Requires a single test task or a single gradle worker (prevents mangled output).

The following properties can be used to configure the randomizedtesting
unit test randomization framework.

* ```tests.asserts```: The desired assertions status for RequireAssertionsRule (true/false).
* ```tests.filter```: Apply test group filtering using Boolean expressions.
* ```tests.iters```: Repeats randomized tests the provided number of times.
* ```tests.seed```: Root seed for the randomizedtesting framework. Default: randomly computed.
* ```tests.stackfiltering```: Enable or disable stack filtering.
* ```tests.timeoutSuite```: Test suite timeout (in millis).
* ```tests.timeout```: Test timeout (in millis).

All of these are build options so you can tweak them for the project but also
modify their defaults using command line, environment variables or your local, non-versioned
```.options.local.properties``` options file. For example:
```shell
./gradlew test -Ptests.rerun=true "-Ptests.jvmargs=-verbose:gc" -Ptests.verbose=true --max-workers=1
```

Plugin: ```com.carrotsearch.gradle.buildinfra.conventions.ApplyReproducibleBuildsPlugin```
--

Sets up sane defaults for all ```AbstractArchiveTask``` tasks. These include predictable file order,
no timestamps and constant (unix) file permissions.

Plugin: ```com.carrotsearch.gradle.buildinfra.conventions.ApplyRegisterCommonTasksPlugin```
--

Configures common convention tasks for all projects.

* ```tidy```: apply all convention-required cleanups (like code formatting, etc.).

Plugin: ```com.carrotsearch.gradle.buildinfra.conventions.ApplyForbiddenApisPlugin```
--

Sets up (forbidden-apis)[https://github.com/policeman-tools/forbidden-apis] API checker
for all Java projects.

This plugin configures forbidden-apis tasks so that for any source set of
a Java project, its dependencies are verified against
forbidden API signatures in plain text
files relative to ```forbiddenApisDir``` build option 
directory (default: ``gradle/forbidden-apis```).

For example, a Guava dependency would correspond to ```gradle/forbidden-apis/com.google.guava-guava.txt```
file.

Hooks up to the ```check``` task to verify code compliance.

Plugin: ```com.carrotsearch.gradle.buildinfra.conventions.ApplySpotlessFormattingPlugin```
--

Sets up (spotless)[https://github.com/diffplug/spotless] to reformat Java and other files
when ```tidy``` task is run. Hooks up to the ```check``` task to verify code compliance.

Google Java Formatter is used.

Plugin: ```com.carrotsearch.gradle.buildinfra.dependencychecks.DependencyChecksPlugin```
--

Generates a top-level ```versions.lock``` dependency lock file for selected
configurations of Java projects. 

Adds the following tasks:
* ```checkLocks```: verifies if the lock file is up to date. Attached as a dependency of ```check```.
* ```writeLocks```: writes an up to date lock file.

Adds the following extension:
```groovy
dependencyVersionChecks {
  lockFileComment = ""
  configurationGroups {
      "group1" {
          include "configuration-name"
          include "another-configuration-name"
      }
      "group2" {
          include "configuration-name"
      }
  }
}
```

A named configuration group consists of one or more configuration names in the project. The same
dependency must have the exact same version across all projects with the same configuration
group. Typically, all Java projects in a multi-module build would require version consistency
for the following configurations:
```
annotationProcessor
compileClasspath
runtimeClasspath
testCompileClasspath
testRuntimeClasspath
```

This plugin only tracks dependency versions and detects their inconsistencies: any resolution
of inconsistencies should be done using Gradle's built-in infrastructure. 

Plugin: ```com.carrotsearch.gradle.buildinfra.conventions.ApplySaneJavaDefaultsPlugin```
--

This plugin does the following:
* configures all ```JavaCompile``` and ```Javadoc``` tasks to use UTF-8.
* reads ```minJava``` version string from the ```libs``` version catalog and
  sets the ```sourceCompatibility```, ```targetCompatibility``` and toolchain's
  version to this string.
