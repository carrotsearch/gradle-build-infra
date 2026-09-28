package com.carrotsearch.gradle.buildinfra.tasktimes

import com.carrotsearch.gradle.buildinfra.AbstractIntegTest
import org.gradle.testkit.runner.TaskOutcome

class MeasureTaskTimesPluginSpec extends AbstractIntegTest {
  static final String SUMMARY = "Summary of task execution times"

  /**
   * Two subprojects with a slow and a quick task each; the plugin is applied by class
   * to avoid the environment checks of the umbrella plugin.
   */
  void taskTimesFixture(String extraBuildScript = "") {
    settingsFile("""
      rootProject.name = 'test-root'
      include 'a', 'b'
    """)
    // Gradle requires project directories to exist.
    file("a/build.gradle", "")
    file("b/build.gradle", "")

    buildFile("""
      plugins {
        id 'com.carrotsearch.gradle.buildinfra' apply false
      }

      apply plugin: com.carrotsearch.gradle.buildinfra.tasktimes.MeasureTaskTimesPlugin

      subprojects {
        tasks.register("slow") {
          doLast { Thread.sleep(150) }
        }
        tasks.register("quick") {
          doLast { }
        }
      }

      ${extraBuildScript}
    """)
  }

  static final String DURATION = /\d+(d|h|m|s|ms)( \d+(d|h|m|s|ms))*/

  /** Report lines: a right-aligned duration followed by the task name. */
  static List<String> reportLines(String output) {
    return output.readLines().findAll { it ==~ /^\s*${DURATION}\s+\S+\s*$/ }
  }

  def "summary is aggregated by task name and survives the configuration cache"() {
    given:
    taskTimesFixture()

    when:
    def result = gradleRunnerWithCi(false, "slow", "quick",
        "-Pbuildinfra.taskTimes.minTimeMillis=0").build()

    then:
    result.task(":b:quick").outcome == TaskOutcome.SUCCESS
    containsLines(result.output,
        "Summary of task execution times (top-20, aggregated by unique name, possibly running in parallel):")
    def lines = reportLines(result.output)
    lines.size() == 2
    lines[0].endsWith(" slow")
    lines[1].endsWith(" quick")
    lines.every { !it.contains(":") }

    when: "the build is re-run from the configuration cache"
    def cached = gradleRunnerWithCi(false, "slow", "quick",
        "-Pbuildinfra.taskTimes.minTimeMillis=0").build()

    then:
    containsLines(cached.output, "Reusing configuration cache.")
    reportLines(cached.output).size() == 2
  }

  def "unique task paths and limit"() {
    given:
    taskTimesFixture()

    when:
    def result = gradleRunnerWithCi(false, "slow", "quick",
        "-Pbuildinfra.taskTimes.aggregate=false", "-Pbuildinfra.taskTimes.limit=2").build()

    then:
    containsLines(result.output, "Summary of task execution times (top-2, >100ms, unique task paths):")
    def lines = reportLines(result.output)
    lines.size() == 2
    lines.collect { it.split(/\s+/).last() }.toSorted() == [":a:slow", ":b:slow"]
  }

  def "no summary is printed if any task failed"() {
    given:
    taskTimesFixture("""
      tasks.register("boom") {
        doLast { throw new GradleException("boom") }
      }
    """)

    when:
    def result = gradleRunnerWithCi(false, "slow", "boom", "--continue").buildAndFail()

    then:
    result.task(":a:slow").outcome == TaskOutcome.SUCCESS
    result.task(":boom").outcome == TaskOutcome.FAILED
    !result.output.contains(SUMMARY)
  }

  def "is applied by the umbrella plugin"() {
    given:
    rootPluginFixture()
    buildFile("""
      plugins {
        id 'com.carrotsearch.gradle.buildinfra'
      }
    """)

    when:
    def result = gradleRunnerWithCi(true, "noop", "-Pbuildinfra.taskTimes.minTimeMillis=0").build()

    then:
    result.task(":noop").outcome in [TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE]
    containsLines(result.output, "Summary of task execution times (top-20")
    reportLines(result.output).any { it.endsWith(" noop") }
  }
}
