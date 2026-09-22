package com.carrotsearch.gradle.buildinfra

import com.carrotsearch.gradle.buildinfra.publishing.mavencentral.AbstractIntegTest
import org.gradle.testkit.runner.TaskOutcome

class BuildInfraExtensionSpec extends AbstractIntegTest {
  /**
   * The root plugin verifies the wrapper and expects a 'libs' catalog with a few versions,
   * so a minimal fixture satisfying these is required.
   */
  void rootPluginFixture() {
    file("gradle/wrapper/gradle-wrapper.properties", """
      distributionUrl=https\\://services.gradle.org/distributions/gradle-${System.getProperty("tests.gradle.version")}-bin.zip
    """)

    file("gradle/libs.versions.toml", """
      [versions]
      googleJavaFormat = "1.35.0"
      minJava = "17"
    """)
  }

  def "exposes injected services via the extension"() {
    given:
    rootPluginFixture()
    file("input/hello.txt", "hello")
    buildFile("""
      plugins {
        id 'com.carrotsearch.gradle.buildinfra'
      }

      def fileOps = buildinfra.fileOps
      def execOps = buildinfra.execOps
      def outDir = layout.buildDirectory.dir("copied")

      tasks.register("copyViaExtension") {
        doLast {
          fileOps.copy {
            from 'input'
            into outDir
          }
        }
      }

      tasks.register("execViaExtension") {
        doLast {
          def out = new ByteArrayOutputStream()
          execOps.exec {
            commandLine 'java', '-version'
            standardOutput = out
            errorOutput = out
          }
          logger.lifecycle("exec output present: " + !out.toString().isBlank())
        }
      }
    """)

    when:
    def result = gradleRunner("copyViaExtension", "execViaExtension").build()

    then:
    result.task(":copyViaExtension").outcome == TaskOutcome.SUCCESS
    result.task(":execViaExtension").outcome == TaskOutcome.SUCCESS
    new File(testProjectDir, "build/copied/hello.txt").getText("UTF-8") == "hello"
    containsLines(result.output, "exec output present: true")

    when: "the build is re-run from the configuration cache"
    new File(testProjectDir, "build/copied").deleteDir()
    def cached = gradleRunner("copyViaExtension", "execViaExtension").build()

    then:
    cached.task(":copyViaExtension").outcome == TaskOutcome.SUCCESS
    containsLines(cached.output, "Reusing configuration cache.")
    new File(testProjectDir, "build/copied/hello.txt").getText("UTF-8") == "hello"
  }
}
