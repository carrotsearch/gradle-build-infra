Migration notes
==

Notes for builds using ```com.carrotsearch.gradle.buildinfra``` when upgrading between versions.

0.0.27
--

### Renamed build options

Build options now follow one convention: ```buildinfra.<plugin>``` switches a sub-plugin on or off
and ```buildinfra.<plugin>.*``` are that plugin's own options. The old names below **fail the
build** with a message naming the replacement. Update ```build-options.properties```,
```gradle.properties```, CI environment variables and scripts accordingly; behaviour is otherwise
unchanged. The ```tests.*``` options of the testing plugin (```tests.seed```, ```tests.jvms```,
```tests.htmlReports```, ...) are untouched.

| Deprecated | Replacement |
|---|---|
| ```task.times``` | ```buildinfra.taskTimes``` |
| ```task.times.aggregate``` | ```buildinfra.taskTimes.aggregate``` |
| ```task.times.limit``` | ```buildinfra.taskTimes.limit``` |
| ```task.times.mintime.millis``` | ```buildinfra.taskTimes.minTimeMillis``` |
| ```task.times.time.format``` | ```buildinfra.taskTimes.timeFormat``` |
| ```tests.slowestTests``` | ```buildinfra.slowestTests``` |
| ```tests.slowestTests.minTime``` | ```buildinfra.slowestTests.minTime``` |
| ```tests.slowestSuites``` | ```buildinfra.slowestTests.suites``` |
| ```tests.slowestSuites.minTime``` | ```buildinfra.slowestTests.suites.minTime``` |
| ```buildinfra.spotlessGradleGroovyScripts``` | ```buildinfra.spotless.gradleScripts``` |
| ```forbiddenApisDir``` | ```buildinfra.forbiddenApis.dir``` |
| ```check.gradlewrapper.consistency``` | ```buildinfra.gradleWrapper.consistency``` |

### Sub-plugins can be switched off

```buildinfra.<plugin>=false``` (in ```build-options.properties``` or with ```-P```) stops the
umbrella plugin from applying a sub-plugin: ```gitInfo```, ```reproducibleBuilds```,
```forbiddenApis```, ```spotless```, ```javaDefaults```, ```testing```, ```testsSummary```,
```slowestTests```, ```failedTests```, ```dependencyChecks```, ```versionCatalogUpdates``` and
```taskTimes``` (the last one defaults to on for CI builds only, as ```task.times``` did).
Build scripts that configure a switched-off plugin's extension directly (for example a
```spotless { ... }``` block) fail; guard such blocks with ```plugins.withId(...) { ... }```.
See the README for details.

### Moved classes

Sources were rearranged into one package per sub-plugin. Only builds that apply sub-plugins
by class (rather than the umbrella plugin id) are affected:

| Old class | New class |
|---|---|
| ```...buildinfra.environment.GitInfoPlugin``` | ```...buildinfra.gitinfo.GitInfoPlugin``` |
| ```...buildinfra.environment.GradleConsistentWithWrapperPlugin``` | ```...buildinfra.gradlewrapper.GradleConsistentWithWrapperPlugin``` |
| ```...buildinfra.conventions.ApplyRegisterCommonTasksPlugin``` | ```...buildinfra.misc.ApplyRegisterCommonTasksPlugin``` |
| ```...buildinfra.conventions.ApplyReproducibleBuildsPlugin``` | ```...buildinfra.reproduciblebuilds.ApplyReproducibleBuildsPlugin``` |
| ```...buildinfra.conventions.ApplyForbiddenApisPlugin``` | ```...buildinfra.forbiddenapis.ApplyForbiddenApisPlugin``` |
| ```...buildinfra.conventions.ApplySpotlessFormattingPlugin``` | ```...buildinfra.spotless.ApplySpotlessFormattingPlugin``` |
| ```...buildinfra.conventions.ApplySaneJavaDefaultsPlugin``` | ```...buildinfra.javadefaults.ApplySaneJavaDefaultsPlugin``` |
| ```...buildinfra.conventions.ApplyVersionsTomlCleanupsPlugin``` | ```...buildinfra.misc.ApplyVersionsTomlCleanupsPlugin``` |

```...buildinfra.testing.TestingEnvPlugin```, ```...buildinfra.publishing.mavencentral.MavenCentralPublishingPlugin```
and the external ```...buildinfra.buildoptions.*``` classes are unchanged. Plugins new in this
release live in ```...buildinfra.testing``` (end-of-build test reports), ```tasktimes``` and
```optiongroups```.

### Removed

* The ```allTestsSummary``` task (it did not work with the configuration cache). The summary of
  all executed tests is now printed automatically at the end of the build
  (```ShowTestsSummaryAtEndPlugin```).

### New in this release

* End-of-build reports: task execution times (```buildinfra.taskTimes```), the slowest tests and
  suites (```buildinfra.slowestTests```), failed tests with reproduce lines
  (```buildinfra.failedTests```) and the tests summary (```buildinfra.testsSummary```).
* Grouped output of the ```buildOptions``` task (```BuildOptionGroupsPlugin```).
* ```dependencyUpdates``` ignores prerelease versions.
