package com.carrotsearch.gradle.buildinfra.publishing.mavencentral

import java.util.zip.ZipFile
import org.assertj.core.api.Assertions
import org.codehaus.groovy.runtime.StringGroovyMethods
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

abstract class AbstractIntegTest extends Specification {
  @TempDir
  protected File testProjectDir

  private GradleRunner runner

  protected void setup() {
    settingsFile("rootProject.name = 'test'")
  }

  void settingsFile(String text) {
    file("settings.gradle", text)
  }

  void buildFile(String text) {
    file("build.gradle", text)
  }

  File file(String relativePath, String text) {
    def file = new File(testProjectDir, relativePath)
    file.parentFile.mkdirs()
    // Groovy's stripIndent ignores whitespace-only lines (unlike java.lang.String's).
    file.setText(StringGroovyMethods.stripIndent((CharSequence) text).stripLeading(), "UTF-8")
    return file
  }

  void gradleProperties(Map<String, String> properties) {
    def props = new Properties()
    props.putAll(properties)
    new File(testProjectDir, "gradle.properties").withOutputStream { props.store(it, null) }
  }

  /**
   * A multi-project build with two java projects, of which only one (:lib) is published.
   */
  void multiProjectFixture(String version, String extensionBody) {
    settingsFile("""
      rootProject.name = 'test-root'
      include 'lib', 'other'
    """)

    gradleProperties([
      "signingKey": getClass().getResource("/test-only-signing-key.asc").getText("UTF-8"),
      "signingPassword": ""
    ])

    buildFile("""
      plugins {
        id 'com.carrotsearch.gradle.buildinfra' apply false
      }

      apply plugin: com.carrotsearch.gradle.buildinfra.publishing.mavencentral.MavenCentralPublishingPlugin

      allprojects {
        group = 'com.example'
        version = '${version}'
      }

      mavenCentralPublishing {
        ${extensionBody}
      }
    """)

    file("lib/build.gradle", """
      plugins {
        id 'java-library'
      }

      description = "The lib project's description."
    """)

    file("lib/src/main/java/com/example/Lib.java", """
      package com.example;

      /** Lib. */
      public class Lib {}
    """)

    file("other/build.gradle", """
      plugins {
        id 'java-library'
      }
    """)
  }

  GradleRunner gradleRunner(String... arguments) {
    if (runner == null) {
      runner = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withPluginClasspath()
    }
    return runner.withArguments([
      "--configuration-cache",
      "--warning-mode",
      "all",
      "--stacktrace"
    ] + arguments.toList())
  }

  List<String> filesUnder(String relativePath) {
    def dir = new File(testProjectDir, relativePath).toPath()
    List<String> result = []
    dir.eachFileRecurse(groovy.io.FileType.FILES) { result.add(dir.relativize(it).toString().replace('\\', '/')) }
    return result.sort()
  }

  List<String> zipEntries(String relativePath) {
    new ZipFile(new File(testProjectDir, relativePath)).withCloseable { zip ->
      zip.entries().findAll { !it.directory }.collect { it.name }.sort()
    }
  }

  void git(String... args) {
    def process = new ProcessBuilder([
      "git"
    ] + args.toList())
    .directory(testProjectDir)
    .redirectErrorStream(true)
    .start()
    def output = process.inputStream.getText("UTF-8")
    if (process.waitFor() != 0) {
      Assertions.fail("git ${args} failed: ${output}")
    }
  }

  void containsLines(String result, String substring) {
    if (!normalizeLines(result).contains(normalizeLines(substring.trim()))) {
      Assertions.fail(String.format(Locale.ROOT,
          "Expecting:%n%n%s%n%nin the following:%n%n%s", substring, result))
    }
  }

  static String normalizeLines(String input) {
    return input.split("\n").collect { line -> line.trim() }.join("\n")
  }
}
