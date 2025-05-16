package com.carrotsearch.gradle.dependencychecks.it

import org.gradle.testkit.runner.TaskOutcome

class BuildOptionsPluginSpec extends AbstractIntegTest {
  def "provides buildOptions extension and configures basic options"() {
    given:
    buildFile(
        """
        plugins {
          id('com.carrotsearch.gradle.buildinfra') apply false
        }
        apply plugin: com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin

        buildOptions {
          addOption("foo", "foo desc", "default-foo")
          addOption("bar", "bar desc",)
        }
        """)

    when:
    def result = gradleRunner()
        .withArguments("buildOptions")
        .run()

    then:
    result.task(":buildOptions").outcome == TaskOutcome.SUCCESS
    containsLines(result.output, """
    bar = [empty]  # bar desc
    foo = default-foo # foo desc
    """)
  }
}