Reusable, opinionated gradle build infrastructure
==

This plugin configures a number of build aspects the way the author likes it. These
aspects include source formatting, dependency management, build options 
and other build scaffolding utilities.

Please take and use whatever you like. Contributions are welcome but if you need
extensive changes, please fork and tweak to your liking.

The following chapters describe sub-plugins that are applied to the build. See
[MIGRATION.md](MIGRATION.md) for notes on upgrading between versions.

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

### Enabling and disabling sub-plugins

Each sub-plugin can be switched off with a boolean build option named ```buildinfra.<plugin>```
(all default to ```true```, except ```buildinfra.taskTimes``` which defaults to ```true``` only when
the ```CI``` environment variable is set). A plugin's own options use the same prefix:
```buildinfra.<plugin>.*```. Like all build options, the switches can be versioned in
```build-options.properties``` or passed on the command line:

```
# build-options.properties
buildinfra.spotless=false
buildinfra.dependencyChecks=false
```
```shell
./gradlew check -Pbuildinfra.testing=false
```

| Option | Sub-plugin |
|---|---|
| ```buildinfra.gitInfo``` | ```GitInfoPlugin``` |
| ```buildinfra.reproducibleBuilds``` | ```ApplyReproducibleBuildsPlugin``` |
| ```buildinfra.forbiddenApis``` | ```ApplyForbiddenApisPlugin``` |
| ```buildinfra.spotless``` | ```ApplySpotlessFormattingPlugin``` |
| ```buildinfra.javaDefaults``` | ```ApplySaneJavaDefaultsPlugin``` |
| ```buildinfra.testing``` | ```TestingEnvPlugin``` |
| ```buildinfra.testsSummary``` | ```ShowTestsSummaryAtEndPlugin``` |
| ```buildinfra.slowestTests``` | ```ShowSlowestTestsAtEndPlugin``` |
| ```buildinfra.failedTests``` | ```ShowFailedTestsAtEndPlugin``` (applies ```TestingEnvPlugin``` itself) |
| ```buildinfra.dependencyChecks``` | ```DependencyChecksPlugin``` |
| ```buildinfra.versionCatalogUpdates``` | ```ApplyVersionsTomlCleanupsPlugin``` |
| ```buildinfra.taskTimes``` | ```MeasureTaskTimesPlugin``` |

The switches only affect what the umbrella plugin applies automatically: a sub-plugin applied
by class from a build script is always active. Build scripts that configure a sub-plugin's
extension directly (for example a ```spotless { ... }``` block) should guard it with
```plugins.withId('com.diffplug.spotless') { ... }``` if the plugin may be switched off. Build options, option groups, the ```tidy```
task and the gradle wrapper check are always applied. Options that were renamed to follow this
convention fail the build if the old name is still used (the message names the replacement);
see [MIGRATION.md](MIGRATION.md).

Plugin: ```com.carrotsearch.gradle.buildinfra.gradlewrapper.GradleConsistentWithWrapperPlugin```
--

Verifies that the gradle version running the build matches the version in
```gradle/wrapper/gradle-wrapper.properties```. Build option ```buildinfra.gradleWrapper.consistency```:
```exact``` (default), ```major```, ```base``` or ```off```.

Plugin: ```com.carrotsearch.gradle.buildinfra.gitinfo.GitInfoPlugin```
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

Plugin: ```com.carrotsearch.gradle.buildinfra.optiongroups.BuildOptionGroupsPlugin```
--

Groups the build options declared by buildinfra plugins into logical categories in the
output of the ```buildOptions``` task (build environment, build control, test reports,
test JVMs, randomized testing). Projects can add their own groups; options matching none of
the groups are listed under "Other options":

```groovy
tasks.withType(com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsTask).configureEach {
  optionGroups {
    group("My project's options", /myproject\.(.*)/)
  }
}
```

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
* ```tests.maxheap```: Maximum heap size for test JVMs.
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

Plugin: ```com.carrotsearch.gradle.buildinfra.testing.ShowTestsSummaryAtEndPlugin```
--

Prints a summary of all executed test tasks (across all projects) at the end of the build,
for example: ```6 test tasks executed, 5552 tests, 2 failures, 7 ignored```.

Plugin: ```com.carrotsearch.gradle.buildinfra.testing.ShowSlowestTestsAtEndPlugin```
--

Prints the slowest tests and test suites (across all projects, top 10) at the end of
a successful build. Build options:

* ```buildinfra.slowestTests.minTime```: minimum test time to consider a test slow (millis). Default: ```500```
* ```buildinfra.slowestTests.suites```: print the summary of the slowest suites. Default: ```true```
* ```buildinfra.slowestTests.suites.minTime```: minimum suite time to consider a suite slow (millis). Default: ```1000```

Plugin: ```com.carrotsearch.gradle.buildinfra.testing.ShowFailedTestsAtEndPlugin```
--

Prints all failed tests (across all projects, top 10), along with the file containing the
test's output and a reproduce line, at the end of the build:

```
ERROR: 1 test has failed:

  - com.example.ExampleTest.failingTest (:lib:test)
    Test output: /path/to/lib/build/test-outputs/test/OUTPUT-com.example.ExampleTest.txt
    Reproduce with: ./gradlew :lib:test --tests com.example.ExampleTest.failingTest -Ptests.seed=6A2E2AB1D2B37F76
```

These three plugins are applied to all projects automatically; they use build services
and work with the configuration cache.

Plugin: ```com.carrotsearch.gradle.buildinfra.reproduciblebuilds.ApplyReproducibleBuildsPlugin```
--

Sets up sane defaults for all ```AbstractArchiveTask``` tasks. These include predictable file order,
no timestamps and constant (unix) file permissions.

Plugin: ```com.carrotsearch.gradle.buildinfra.misc.ApplyRegisterCommonTasksPlugin```
--

Configures common convention tasks for all projects.

* ```tidy```: apply all convention-required cleanups (like code formatting, etc.).

Plugin: ```com.carrotsearch.gradle.buildinfra.forbiddenapis.ApplyForbiddenApisPlugin```
--

Sets up (forbidden-apis)[https://github.com/policeman-tools/forbidden-apis] API checker
for all Java projects.

This plugin configures forbidden-apis tasks so that for any source set of
a Java project, its dependencies are verified against
forbidden API signatures in plain text
files relative to ```buildinfra.forbiddenApis.dir``` build option 
directory (default: ``gradle/forbidden-apis```).

For example, a Guava dependency would correspond to ```gradle/forbidden-apis/com.google.guava-guava.txt```
file.

Hooks up to the ```check``` task to verify code compliance.

Plugin: ```com.carrotsearch.gradle.buildinfra.spotless.ApplySpotlessFormattingPlugin```
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

Plugin: ```com.carrotsearch.gradle.buildinfra.javadefaults.ApplySaneJavaDefaultsPlugin```
--

This plugin does the following:
* configures all ```JavaCompile``` and ```Javadoc``` tasks to use UTF-8.
* reads ```minJava``` version string from the ```libs``` version catalog and
  sets the ```sourceCompatibility```, ```targetCompatibility``` and toolchain's
  version to this string.

Plugin: ```com.carrotsearch.gradle.buildinfra.tasktimes.MeasureTaskTimesPlugin```
--

Applied automatically to the root project. Measures wall-clock execution time of all
executed tasks (across all projects) and prints a summary of the slowest ones at the end
of a successful build (no summary is printed if any task failed). For example:

```
Summary of task execution times (top-20, >100ms, aggregated by unique name, possibly running in parallel):
    1m 12s  test
       12s  compileJava
     300ms  spotlessJavaCheck
```

Build options:

* ```buildinfra.taskTimes```: print the summary at the end of the build. Default: ```false```, or
  ```true``` when the ```CI``` environment variable is set (CI builds).
* ```buildinfra.taskTimes.aggregate```: aggregate task times by unique task name (across all projects); if
  ```false```, unique task paths are listed instead. Default: ```true```
* ```buildinfra.taskTimes.limit```: limit the list to the top-N tasks. Default: ```20```
* ```buildinfra.taskTimes.minTimeMillis```: omit tasks that took less than this number of milliseconds
  (```0``` reports all tasks). Default: ```100```
* ```buildinfra.taskTimes.timeFormat```: format of reported durations: ```compact``` (```1m 5s```),
  ```normal``` (```1m 5s 300ms```) or ```full``` (```1 minute 5 seconds 300 milliseconds```).
  Default: ```compact```

For example:
```shell
./gradlew build -Pbuildinfra.taskTimes=true -Pbuildinfra.taskTimes.aggregate=false -Pbuildinfra.taskTimes.limit=10
```

Plugin: ```com.carrotsearch.gradle.buildinfra.publishing.mavencentral.MavenCentralPublishingPlugin```
--

Publishes selected projects of a build to Maven Central via
[Sonatype Central Portal](https://central.sonatype.org/publish/publish-portal-api/):

* release versions are published to a local, build-relative Maven repository, zipped into
  a single bundle and uploaded using Central Portal's publisher API; the plugin then (optionally)
  waits until the deployment passes validation,
* ```-SNAPSHOT``` versions are published directly to Central Portal's snapshot repository
  (snapshots have to be enabled for the namespace in Central Portal).

This plugin is *not* applied automatically. Apply it to the root project by class,
then declare which projects should be published and what should go into their POMs.

```groovy
plugins {
  id "com.carrotsearch.gradle.buildinfra" version "$version" apply false
}

apply plugin: com.carrotsearch.gradle.buildinfra.publishing.mavencentral.MavenCentralPublishingPlugin

mavenCentralPublishing {
  // Paths of projects to publish (':' is the root project).
  projects = [':foo', ':bar']

  // POM content shared by all published projects (plain MavenPom).
  pom {
    url = 'https://github.com/example/foobar'
    licenses {
      license {
        name = 'Apache 2'
        url = 'https://www.apache.org/licenses/LICENSE-2.0.txt'
      }
    }
    developers {
      developer {
        id = 'jdoe'
        name = 'John Doe'
        email = 'jdoe@example.com'
      }
    }
    scm {
      connection = 'scm:git:git@github.com:example/foobar.git'
      developerConnection = 'scm:git:git@github.com:example/foobar.git'
      url = 'https://github.com/example/foobar'
    }
  }

  // Adds a project to publish, with project-specific POM tweaks. These are always applied
  // after the shared POM configuration.
  project(':baz') {
    pom {
      name = 'Baz'
      description = 'The Baz library.'
    }
  }
}
```

POM's ```name``` and ```description``` default to the project's name and description.

Each published project gets ```maven-publish``` and ```signing``` plugins applied. Projects with
the ```java``` plugin also get sources and javadoc jars and a ```jars``` publication of the ```java```
component (with the artifact identifier set to ```base.archivesName```). Any other Maven publication
declared in a published project is signed, configured and published in the same way.

Tasks added to the root project:

* ```publishToMavenCentral``` - publishes everything: a bundle upload for release versions,
  the snapshot repository for snapshot versions.
* ```publishLocal``` - publishes all artifacts to ```build/maven``` (always cleaned first).
* ```prepareMavenCentralBundle``` - creates ```build/maven-bundle/bundle-${bundleName}-${version}.zip```,
  with ```maven-metadata.xml``` files and unwanted checksums filtered out. Useful for inspecting what
  would be uploaded.
* ```uploadMavenCentralBundle``` - uploads the bundle and waits for validation.
* ```checkCleanCheckout``` - fails if the git checkout has any modified or untracked files
  (uses ```GitInfoPlugin```, which is applied automatically).

The version of the root project decides whether the build is a release or a snapshot.

Credentials and signing: Central Portal [user token](https://central.sonatype.org/publish/generate-portal-token/)
is read from ```mavenCentralUsername``` and ```mavenCentralPassword``` gradle properties
(```nexusUsername``` and ```nexusPassword``` are used as a fallback). These can be set in
```~/.gradle/gradle.properties``` or passed via ```ORG_GRADLE_PROJECT_mavenCentralUsername``` and
```ORG_GRADLE_PROJECT_mavenCentralPassword``` environment variables.

Signatures are required for releases and optional for snapshots. The signing plugin's
[default configuration](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signatory_credentials)
(```signing.keyId```, ```signing.password```, ```signing.secretKeyRingFile```) is used, unless
```signingKey``` (ascii-armored private key), ```signingPassword``` and, optionally, ```signingKeyId```
gradle properties are present - these configure in-memory keys, which is convenient on a CI.

Other options (all are optional, the defaults are shown):

```groovy
mavenCentralPublishing {
  // The name of the bundle file and of the deployment (suffixed with the version).
  bundleName = rootProject.name

  // AUTOMATIC: release to Maven Central once validated.
  // USER_MANAGED: validate, then wait for a manual "publish" (or "drop") in Central Portal.
  publishingType = 'AUTOMATIC'

  // How long to wait until the deployment is validated (USER_MANAGED) or is being
  // published (AUTOMATIC). The build fails if the validation fails. Set to
  // Duration.ZERO to upload and not wait at all.
  validationTimeout = java.time.Duration.ofMinutes(30)
  statusPollInterval = java.time.Duration.ofSeconds(5)

  // Releases can only be published from a clean git checkout.
  requireCleanCheckout = true

  // Publish gradle module metadata (*.module files).
  gradleModuleMetadata = true

  // Checksums to include in the bundle. Maven Central requires md5 and sha1; sha256 and
  // sha512 are optional (and count towards Central Portal's published file limits).
  bundleChecksums = ['md5', 'sha1']

  username = providers.gradleProperty("mavenCentralUsername")
  password = providers.gradleProperty("mavenCentralPassword")

  portalUrl = 'https://central.sonatype.com'
  snapshotsUrl = 'https://central.sonatype.com/repository/maven-snapshots/'
}
```
