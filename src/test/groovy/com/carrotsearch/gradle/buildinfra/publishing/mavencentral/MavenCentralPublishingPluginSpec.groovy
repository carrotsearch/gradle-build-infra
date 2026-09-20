package com.carrotsearch.gradle.buildinfra.publishing.mavencentral

import java.nio.charset.StandardCharsets
import org.gradle.testkit.runner.TaskOutcome

class MavenCentralPublishingPluginSpec extends AbstractIntegTest {
  static final String POM_BODY = """
    pom {
      url = 'https://example.com/test'
      licenses {
        license {
          name = 'Apache 2'
          url = 'https://www.apache.org/licenses/LICENSE-2.0.txt'
        }
      }
      developers {
        developer {
          id = 'jdoe'
          name = 'John Doe'
        }
      }
      scm {
        url = 'https://example.com/test.git'
      }
    }
  """

  def "publishes selected projects to a local repository"() {
    given:
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      ${POM_BODY}
    """)

    when:
    def result = gradleRunner("publishLocal").build()

    then:
    result.task(":lib:publishJarsPublicationToCentralBundleRepository").outcome == TaskOutcome.SUCCESS
    result.task(":other:publishJarsPublicationToCentralBundleRepository") == null

    def files = filesUnder("build/maven/com/example/lib/1.2.3")
    files.containsAll([
      "lib-1.2.3.jar",
      "lib-1.2.3.jar.asc",
      "lib-1.2.3.jar.md5",
      "lib-1.2.3.jar.sha1",
      "lib-1.2.3-sources.jar",
      "lib-1.2.3-sources.jar.asc",
      "lib-1.2.3-javadoc.jar",
      "lib-1.2.3-javadoc.jar.asc",
      "lib-1.2.3.pom",
      "lib-1.2.3.pom.asc",
      "lib-1.2.3.module",
      "lib-1.2.3.module.asc"
    ])
    !new File(testProjectDir, "build/maven/com/example/other").exists()

    def pom = new File(testProjectDir, "build/maven/com/example/lib/1.2.3/lib-1.2.3.pom").getText("UTF-8")
    containsLines(pom, "<name>lib</name>")
    containsLines(pom, "<description>The lib project's description.</description>")
    containsLines(pom, "<url>https://example.com/test</url>")
    containsLines(pom, "<name>John Doe</name>")
  }

  def "applies per-project pom after the shared pom, regardless of declaration order"() {
    given:
    multiProjectFixture("1.2.3", """
      project(':lib') {
        pom {
          name = 'Lib (custom)'
          url = 'https://example.com/lib'
        }
      }
      ${POM_BODY}
      gradleModuleMetadata = false
    """)

    when:
    gradleRunner("publishLocal").build()

    then:
    def files = filesUnder("build/maven/com/example/lib/1.2.3")
    !files.contains("lib-1.2.3.module")

    def pom = new File(testProjectDir, "build/maven/com/example/lib/1.2.3/lib-1.2.3.pom").getText("UTF-8")
    containsLines(pom, "<name>Lib (custom)</name>")
    containsLines(pom, "<url>https://example.com/lib</url>")
    containsLines(pom, "<name>John Doe</name>")
  }

  def "publishes the root project of a single-project build"() {
    given:
    gradleProperties([
      "signingKey": getClass().getResource("/test-only-signing-key.asc").getText("UTF-8")
    ])
    buildFile("""
      plugins {
        id 'java-library'
        id 'com.carrotsearch.gradle.buildinfra' apply false
      }

      apply plugin: com.carrotsearch.gradle.buildinfra.publishing.mavencentral.MavenCentralPublishingPlugin

      group = 'com.example'
      version = '1.0.0'
      base.archivesName = 'renamed'

      mavenCentralPublishing {
        projects ':'
        bundleName = 'single'
      }
    """)

    when:
    gradleRunner("prepareMavenCentralBundle").build()

    then:
    zipEntries("build/maven-bundle/bundle-single-1.0.0.zip").contains("com/example/renamed/1.0.0/renamed-1.0.0.pom")
  }

  def "bundle has no maven metadata and only the configured checksums"() {
    given:
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      ${bundleChecksums}
    """)
    file("build/maven/com/example/stale/0.0.1/stale-0.0.1.pom", "<stale />")

    when:
    def result = gradleRunner("prepareMavenCentralBundle").build()

    then:
    result.task(":mavenLocalClean").outcome == TaskOutcome.SUCCESS
    filesUnder("build/maven").any { it.contains("maven-metadata") }

    def entries = zipEntries("build/maven-bundle/bundle-test-root-1.2.3.zip")
    entries.every { it.startsWith("com/example/lib/1.2.3/lib-1.2.3") }
    entries.contains("com/example/lib/1.2.3/lib-1.2.3.jar.asc")
    !entries.any { it.contains("maven-metadata") }
    !entries.any { it.contains(".asc.") }
    def extensions = entries.collect { it.substring(it.lastIndexOf('.') + 1) }.toSet()
    extensions == ([
      "jar",
      "pom",
      "module",
      "asc"
    ] + expectedChecksums).toSet()

    where:
    bundleChecksums                                | expectedChecksums
    ""                                             | [
      "md5",
      "sha1"
    ]
    "bundleChecksums = ['md5', 'sha1', 'sha512']"  | [
      "md5",
      "sha1",
      "sha512"
    ]
  }

  def "uploads the bundle and waits until it is published"() {
    given:
    def portal = new CentralPortalStub()
    portal.deploymentStates.addAll([
      "PENDING",
      "VALIDATING",
      "VALIDATED",
      "PUBLISHING"
    ])
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      requireCleanCheckout = false
      portalUrl = '${portal.url}'
      statusPollInterval = java.time.Duration.ofMillis(50)
    """)

    when:
    def result = gradleRunner("publishToMavenCentral", "-PmavenCentralUsername=user", "-PmavenCentralPassword=secret")
        .build()

    then:
    result.task(":checkCleanCheckout").outcome == TaskOutcome.SKIPPED
    result.task(":uploadMavenCentralBundle").outcome == TaskOutcome.SUCCESS
    result.output.contains("Deployment ID: deployment-id-1234")
    result.output.contains("Deployment state: PUBLISHING")
    !result.output.contains("secret")

    def uploads = portal.requestsTo("/upload")
    uploads.size() == 1
    def upload = uploads[0]
    upload.method == "POST"
    upload.query == "publishingType=AUTOMATIC&name=test-root-1.2.3"
    upload.headers["authorization"] == "Bearer " + "user:secret".bytes.encodeBase64()
    upload.headers["content-type"].startsWith("multipart/form-data; boundary=")
    upload.headers["content-length"] == upload.body.length.toString()

    def bundle = new File(testProjectDir, "build/maven-bundle/bundle-test-root-1.2.3.zip").bytes
    def boundary = upload.headers["content-type"].split("boundary=")[1]
    def body = new String(upload.body, StandardCharsets.ISO_8859_1)
    body.startsWith("--${boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"bundle-test-root-1.2.3.zip\"\r\n")
    body.endsWith("\r\n--${boundary}--\r\n")
    body.contains(new String(bundle, StandardCharsets.ISO_8859_1))

    def statusChecks = portal.requestsTo("/status")
    statusChecks.size() == 4
    statusChecks.every { it.method == "POST" && it.query == "id=deployment-id-1234" }

    cleanup:
    portal.close()
  }

  def "user-managed deployments are only awaited until validated"() {
    given:
    def portal = new CentralPortalStub()
    portal.deploymentStates.addAll([
      "VALIDATING",
      "VALIDATED",
      "PUBLISHING"
    ])
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      requireCleanCheckout = false
      publishingType = 'USER_MANAGED'
      portalUrl = '${portal.url}'
      statusPollInterval = java.time.Duration.ofMillis(50)
    """)

    when:
    gradleRunner("publishToMavenCentral", "-PnexusUsername=user", "-PnexusPassword=secret").build()

    then:
    portal.requestsTo("/upload")[0].query == "publishingType=USER_MANAGED&name=test-root-1.2.3"
    portal.requestsTo("/status").size() == 2

    cleanup:
    portal.close()
  }

  def "does not wait for validation with zero timeout"() {
    given:
    def portal = new CentralPortalStub()
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      requireCleanCheckout = false
      portalUrl = '${portal.url}'
      validationTimeout = java.time.Duration.ZERO
    """)

    when:
    gradleRunner("publishToMavenCentral", "-PmavenCentralUsername=user", "-PmavenCentralPassword=secret").build()

    then:
    portal.requestsTo("/upload").size() == 1
    portal.requestsTo("/status").isEmpty()

    cleanup:
    portal.close()
  }

  def "fails when the deployment fails validation"() {
    given:
    def portal = new CentralPortalStub()
    portal.deploymentStates.addAll([
      "VALIDATING",
      "FAILED"
    ])
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      requireCleanCheckout = false
      portalUrl = '${portal.url}'
      statusPollInterval = java.time.Duration.ofMillis(50)
    """)

    when:
    def result = gradleRunner("publishToMavenCentral", "-PmavenCentralUsername=user", "-PmavenCentralPassword=secret")
        .buildAndFail()

    then:
    result.output.contains("Deployment deployment-id-1234 failed validation")
    result.output.contains("Stub validation error.")

    cleanup:
    portal.close()
  }

  def "fails when the upload is rejected or validation times out"() {
    given:
    def portal = new CentralPortalStub()
    portal.uploadResponseCode = uploadResponseCode
    portal.uploadResponseBody = uploadResponseBody
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      requireCleanCheckout = false
      portalUrl = '${portal.url}'
      statusPollInterval = java.time.Duration.ofMillis(50)
      validationTimeout = java.time.Duration.ofMillis(300)
    """)

    when:
    def result = gradleRunner("publishToMavenCentral", "-PmavenCentralUsername=user", "-PmavenCentralPassword=secret")
        .buildAndFail()

    then:
    result.output.contains(expectedMessage)

    cleanup:
    portal.close()

    where:
    uploadResponseCode | uploadResponseBody | expectedMessage
    401                | "Go away."         | "Central Portal responded with HTTP 401: Go away."
    201                | "id-5"             | "Timed out waiting for deployment id-5 to be validated (last state: PENDING)"
  }

  def "fails without credentials"() {
    given:
    def portal = new CentralPortalStub()
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      requireCleanCheckout = false
      portalUrl = '${portal.url}'
    """)

    when:
    def result = gradleRunner("publishToMavenCentral").buildAndFail()

    then:
    result.output.contains("Central Portal credentials are required")
    portal.requests.isEmpty()

    cleanup:
    portal.close()
  }

  def "snapshots are published to the snapshot repository"() {
    given:
    def portal = new CentralPortalStub()
    def snapshotsRepo = new File(testProjectDir, "snapshots-repo")
    multiProjectFixture("1.2.3-SNAPSHOT", """
      projects = [':lib']
      requireCleanCheckout = false
      portalUrl = '${portal.url}'
      snapshotsUrl = '${snapshotsRepo.toURI()}'
    """)
    // No signing key: snapshots don't require signatures.
    gradleProperties([:])

    when:
    def result = gradleRunner("publishToMavenCentral").build()

    then:
    result.task(":lib:publishJarsPublicationToCentralSnapshotsRepository").outcome == TaskOutcome.SUCCESS
    result.task(":uploadMavenCentralBundle") == null
    result.task(":checkCleanCheckout") == null
    portal.requests.isEmpty()

    def files = filesUnder("snapshots-repo/com/example/lib/1.2.3-SNAPSHOT")
    files.any { it ==~ /lib-1\.2\.3-.*\.jar/ }
    !files.any { it.endsWith(".asc") }

    when:
    result = gradleRunner("uploadMavenCentralBundle", "-PmavenCentralUsername=user", "-PmavenCentralPassword=secret")
        .buildAndFail()

    then:
    result.output.contains("Snapshot versions can't be uploaded")
    portal.requests.isEmpty()

    cleanup:
    portal.close()
  }

  def "requires a clean git checkout for releases"() {
    given:
    def portal = new CentralPortalStub()
    portal.deploymentStates.add("PUBLISHED")
    multiProjectFixture("1.2.3", """
      projects = [':lib']
      portalUrl = '${portal.url}'
    """)
    file(".gitignore", """
      .gradle
      build
    """)
    def runner = gradleRunner("publishToMavenCentral", "-PmavenCentralUsername=user", "-PmavenCentralPassword=secret")

    when: "untracked files"
    git("init", "-q")
    def result = runner.buildAndFail()

    then:
    result.output.contains("Seems like your git checkout isn't clean?")
    result.output.contains("? build.gradle")
    portal.requests.isEmpty()

    when: "everything committed"
    git("add", "-A")
    git("-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "initial")
    result = runner.build()

    then:
    result.task(":checkCleanCheckout").outcome == TaskOutcome.SUCCESS
    portal.requestsTo("/upload").size() == 1

    cleanup:
    portal.close()
  }

  def "can only be applied to the root project"() {
    given:
    settingsFile("include 'sub'")
    buildFile("""
      plugins {
        id 'com.carrotsearch.gradle.buildinfra' apply false
      }
    """)
    file("sub/build.gradle", """
      apply plugin: com.carrotsearch.gradle.buildinfra.publishing.mavencentral.MavenCentralPublishingPlugin
    """)

    when:
    def result = gradleRunner("help").buildAndFail()

    then:
    result.output.contains("applicable to the rootProject only (currently applied to :sub)")
  }
}
