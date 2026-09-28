package com.carrotsearch.gradle.buildinfra.testing

import com.carrotsearch.gradle.buildinfra.AbstractIntegTest
import org.gradle.testkit.runner.TaskOutcome

/**
 * End-of-build test reports: the tests summary, the slowest tests and the failed tests.
 */
class TestReportsAtEndSpec extends AbstractIntegTest {
  static final String SLOWEST_TESTS = "The slowest tests during this run:"
  static final String SLOWEST_SUITES = "The slowest suites during this run:"

  /**
   * A java subproject with a slow, a skipped and a (conditionally) failing test, applying
   * the umbrella plugin. Tests are always re-run so that every build produces the reports.
   */
  void javaProjectFixture() {
    rootPluginFixture()
    settingsFile("""
      rootProject.name = 'test-root'
      include 'lib'
    """)
    buildFile("""
      plugins {
        id 'com.carrotsearch.gradle.buildinfra'
      }
    """)
    file("lib/build.gradle", """
      plugins {
        id 'java-library'
      }

      repositories {
        mavenCentral()
      }

      dependencies {
        testImplementation 'org.junit.jupiter:junit-jupiter:6.1.3'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher:6.1.3'
      }

      test {
        useJUnitPlatform()
        systemProperty 'fail', providers.gradleProperty('fail').getOrElse('false')
      }
    """)
    file("lib/src/test/java/com/example/ExampleTest.java", """
      package com.example;

      import org.junit.jupiter.api.Assertions;
      import org.junit.jupiter.api.Disabled;
      import org.junit.jupiter.api.Test;

      public class ExampleTest {
        @Test
        public void slowTest() throws Exception {
          Thread.sleep(300);
        }

        @Test
        @Disabled
        public void skippedTest() {}

        @Test
        public void failingTest() {
          Assertions.assertFalse(Boolean.getBoolean("fail"), "Failing on request.");
        }
      }
    """)
  }

  static final String[] RERUN_ALL = ["test", "-Ptests.rerun=true"]

  def "summary and slowest tests are printed and survive the configuration cache"() {
    given:
    javaProjectFixture()
    def args = RERUN_ALL + ["-Pbuildinfra.slowestTests.minTime=0", "-Pbuildinfra.slowestTests.suites.minTime=0"]

    when:
    def result = gradleRunner(args).build()

    then:
    result.task(":lib:test").outcome == TaskOutcome.SUCCESS
    containsLines(result.output, "1 test task executed, 3 tests, 1 ignored")
    containsLines(result.output, SLOWEST_TESTS)
    result.output =~ /\d+ms\s+ExampleTest\.slowTest \(:lib:test\)/
    containsLines(result.output, SLOWEST_SUITES)
    result.output =~ /\d+ms\s+ExampleTest \(:lib:test\)/

    when: "the build is re-run from the configuration cache"
    def cached = gradleRunner(args).build()

    then:
    containsLines(cached.output, "Reusing configuration cache.")
    containsLines(cached.output, "1 test task executed, 3 tests, 1 ignored")
    containsLines(cached.output, SLOWEST_TESTS)
    containsLines(cached.output, SLOWEST_SUITES)
  }

  def "failed tests are listed with their output file and reproduce line"() {
    given:
    javaProjectFixture()

    when:
    def result = gradleRunner(RERUN_ALL + ["-Pfail=true"]).buildAndFail()

    then:
    result.task(":lib:test").outcome == TaskOutcome.FAILED
    containsLines(result.output, "1 test task executed, 3 tests, 1 failure, 1 ignored")
    containsLines(result.output, "ERROR: 1 test has failed:")
    containsLines(result.output, "- com.example.ExampleTest.failingTest (:lib:test)")
    containsLines(result.output, "Reproduce with: ./gradlew :lib:test --tests com.example.ExampleTest.failingTest -Ptests.seed=")
    def outputFile = (result.output =~ /Test output: (.+OUTPUT-com\.example\.ExampleTest\.txt)/)[0][1]
    new File(outputFile).text.contains("Failing on request.")
    !result.output.contains(SLOWEST_TESTS)
    !result.output.contains(SLOWEST_SUITES)
  }

  def "slowest tests and suites can be turned off"() {
    given:
    javaProjectFixture()

    when:
    def result = gradleRunner(RERUN_ALL + [
      "-Pbuildinfra.slowestTests=false",
      "-Pbuildinfra.slowestTests.minTime=0", "-Pbuildinfra.slowestTests.suites.minTime=0"
    ]).build()

    then:
    result.task(":lib:test").outcome == TaskOutcome.SUCCESS
    containsLines(result.output, "1 test task executed, 3 tests, 1 ignored")
    !result.output.contains(SLOWEST_TESTS)
    !result.output.contains(SLOWEST_SUITES)
  }
}
