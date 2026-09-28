package com.carrotsearch.gradle.buildinfra.optiongroups

import com.carrotsearch.gradle.buildinfra.AbstractIntegTest
import org.gradle.testkit.runner.TaskOutcome

class BuildOptionGroupsPluginSpec extends AbstractIntegTest {
  def "build options are listed in groups"() {
    given:
    rootPluginFixture()
    buildFile("""
      plugins {
        id 'java'
        id 'com.carrotsearch.gradle.buildinfra'
      }

      buildOptions.addOption("myproject.option", "A project-specific option.")
    """)

    when:
    def result = gradleRunner("buildOptions").build()

    then:
    result.task(":buildOptions").outcome == TaskOutcome.SUCCESS
    def out = result.output
    def headers = [
      "Build-infra plugins (enable/disable)",
      "Build-infra plugin options",
      "Test reports and output",
      "Test JVMs and execution",
      "Test randomization (randomizedtesting)",
      "Deprecated options (renamed)",
      "Other options"
    ]
    headers.every { out.contains(it) }
    headers.collect { out.indexOf(it) } == headers.collect { out.indexOf(it) }.sort()
    // Each option under its group header.
    out.indexOf("buildinfra.spotless ") in (out.indexOf(headers[0])..out.indexOf(headers[1]))
    out.indexOf("buildinfra.spotless.gradleScripts") in (out.indexOf(headers[1])..out.indexOf(headers[2]))
    out.indexOf("tests.htmlReports") in (out.indexOf(headers[2])..out.indexOf(headers[3]))
    out.indexOf("tests.jvms") in (out.indexOf(headers[3])..out.indexOf(headers[4]))
    out.indexOf("tests.seed") in (out.indexOf(headers[4])..out.indexOf(headers[5]))
    out.indexOf("task.times ") in (out.indexOf(headers[5])..out.indexOf(headers[6]))
    out.indexOf("myproject.option") > out.indexOf(headers[6])
  }
}
