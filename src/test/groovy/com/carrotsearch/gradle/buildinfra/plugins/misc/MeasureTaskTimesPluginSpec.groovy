package com.carrotsearch.gradle.buildinfra.plugins.misc

import com.carrotsearch.gradle.buildinfra.publishing.mavencentral.AbstractIntegTest
import org.gradle.testkit.runner.GradleRunner
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

      apply plugin: com.carrotsearch.gradle.buildinfra.plugins.misc.MeasureTaskTimesPlugin

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

  /**
   * The option's default depends on the CI environment variable, so tests control it
   * explicitly (the test JVM itself may be running on a CI).
   */
  GradleRunner runnerWithCi(boolean ci, String... arguments) {
    def env = new HashMap<String, String>(System.getenv())
    env.remove("CI")
    if (ci) {
      env.put("CI", "true")
    }
    return gradleRunner(arguments).withEnvironment(env)
  }

  static final String DURATION = /\d+(d|h|m|s|ms)( \d+(d|h|m|s|ms))*/

  /** Report lines: a right-aligned duration followed by the task name. */
  static List<String> reportLines(String output) {
    return output.readLines().findAll { it ==~ /^\s*${DURATION}\s+\S+\s*$/ }
  }

  def "no summary is printed by default outside of CI"() {
    given:
    taskTimesFixture()

    when:
    def result = runnerWithCi(false, "slow", "quick").build()

    then:
    result.task(":a:slow").outcome == TaskOutcome.SUCCESS
    !result.output.contains(SUMMARY)
  }

  def "summary is printed by default on CI, unless disabled explicitly"() {
    given:
    taskTimesFixture()

    when:
    def result = runnerWithCi(true, "slow", "quick").build()

    then:
    result.task(":a:slow").outcome == TaskOutcome.SUCCESS
    containsLines(result.output, "Summary of task execution times (top-20")

    when:
    def disabled = runnerWithCi(true, "slow", "quick", "-Ptask.times=false").build()

    then:
    disabled.task(":a:slow").outcome == TaskOutcome.SUCCESS
    !disabled.output.contains(SUMMARY)
  }

  def "summary is aggregated by task name and survives the configuration cache"() {
    given:
    taskTimesFixture()

    when:
    def result = runnerWithCi(false, "slow", "quick",
        "-Ptask.times=true", "-Ptask.times.mintime.millis=0").build()

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
    def cached = runnerWithCi(false, "slow", "quick",
        "-Ptask.times=true", "-Ptask.times.mintime.millis=0").build()

    then:
    containsLines(cached.output, "Reusing configuration cache.")
    reportLines(cached.output).size() == 2
  }

  def "unique task paths and limit"() {
    given:
    taskTimesFixture()

    when:
    def result = runnerWithCi(false, "slow", "quick",
        "-Ptask.times=true", "-Ptask.times.aggregate=false", "-Ptask.times.limit=2").build()

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
    def result = runnerWithCi(false, "slow", "boom", "--continue", "-Ptask.times=true").buildAndFail()

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
    def result = runnerWithCi(true, "noop", "-Ptask.times.mintime.millis=0").build()

    then:
    result.task(":noop").outcome in [TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE]
    containsLines(result.output, "Summary of task execution times (top-20")
    reportLines(result.output).any { it.endsWith(" noop") }
  }
}
