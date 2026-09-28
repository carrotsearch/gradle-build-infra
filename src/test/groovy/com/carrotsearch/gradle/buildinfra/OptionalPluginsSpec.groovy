package com.carrotsearch.gradle.buildinfra

import org.gradle.testkit.runner.TaskOutcome

/** Enabling and disabling sub-plugins with build options; deprecated option names. */
class OptionalPluginsSpec extends AbstractIntegTest {
  static final String TASK_TIMES_SUMMARY = "Summary of task execution times"

  void fixture() {
    rootPluginFixture()
    buildFile("""
      plugins {
        id 'java'
        id 'com.carrotsearch.gradle.buildinfra'
      }
    """)
    file("build-options.properties", """
      buildinfra.spotless=false
      buildinfra.testing=false
      buildinfra.failedTests=false
    """)
  }

  def "sub-plugins can be switched off with build options"() {
    given:
    fixture()

    when:
    def tasks = gradleRunnerWithCi(false, "tasks", "--all").build()

    then:
    tasks.task(":tasks").outcome == TaskOutcome.SUCCESS
    !tasks.output.contains("spotlessCheck")
    !tasks.output.contains("randomizationInfo")
    tasks.output.contains("forbiddenApisMain")
    tasks.output.contains("updateVersions")

    when:
    def options = gradleRunnerWithCi(false, "buildOptions").build()

    then:
    containsLines(options.output, "Build-infra plugins (enable/disable)")
    options.output =~ /buildinfra\.spotless\s+= false/
    options.output =~ /buildinfra\.forbiddenApis\s+= true/

    when: "the command line overrides the properties file"
    def enabled = gradleRunnerWithCi(false, "tasks", "--all", "-Pbuildinfra.spotless=true").build()

    then:
    enabled.output.contains("spotlessCheck")
  }

  def "task times are on by default for CI builds only"() {
    given:
    fixture()

    when:
    def ci = gradleRunnerWithCi(true, "noop").build()

    then:
    containsLines(ci.output, TASK_TIMES_SUMMARY)

    when:
    def local = gradleRunnerWithCi(false, "noop").build()

    then:
    !local.output.contains(TASK_TIMES_SUMMARY)
  }

  def "deprecated option names fail the build"() {
    given:
    fixture()

    when:
    def result = gradleRunnerWithCi(false, "noop", "-Ptask.times=true").buildAndFail()

    then:
    containsLines(result.output, "Build option 'task.times' has been renamed to 'buildinfra.taskTimes'.")
  }
}
