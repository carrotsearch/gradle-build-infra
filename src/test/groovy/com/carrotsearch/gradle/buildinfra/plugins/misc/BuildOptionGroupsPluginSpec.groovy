package com.carrotsearch.gradle.buildinfra.plugins.misc

import com.carrotsearch.gradle.buildinfra.publishing.mavencentral.AbstractIntegTest
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
      "Build environment and conventions",
      "Build control and information",
      "Test reports and output",
      "Test JVMs and execution",
      "Test randomization (randomizedtesting)",
      "Other options"
    ]
    headers.collect { out.indexOf(it) } == headers.collect { out.indexOf(it) }.sort()
    headers.every { out.contains(it) }
    // Each option under its group header.
    out.indexOf("check.gradlewrapper.consistency") in (out.indexOf(headers[0])..out.indexOf(headers[1]))
    out.indexOf("task.times.limit") in (out.indexOf(headers[1])..out.indexOf(headers[2]))
    out.indexOf("tests.slowestTests") in (out.indexOf(headers[2])..out.indexOf(headers[3]))
    out.indexOf("tests.jvms") in (out.indexOf(headers[3])..out.indexOf(headers[4]))
    out.indexOf("tests.seed") in (out.indexOf(headers[4])..out.indexOf(headers[5]))
    out.indexOf("myproject.option") > out.indexOf(headers[5])
  }
}
