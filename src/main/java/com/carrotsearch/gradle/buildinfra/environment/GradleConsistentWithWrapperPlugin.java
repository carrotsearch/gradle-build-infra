package com.carrotsearch.gradle.buildinfra.environment;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsExtension;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;
import org.gradle.util.GradleVersion;

public class GradleConsistentWithWrapperPlugin extends AbstractPlugin {
  @Inject
  public GradleConsistentWithWrapperPlugin(Problems problems) {
    super(problems);
  }

  enum ConsistencyOptions {
    EXACT,
    MAJOR,
    BASE,
    OFF;
  }

  @Override
  public void apply(Project project) {
    super.pluginAppliedToRootProject(project);

    project.getPlugins().apply(BuildOptionsPlugin.class);
    var buildOptions = project.getExtensions().getByType(BuildOptionsExtension.class);
    var optionValue =
        ConsistencyOptions.valueOf(
            buildOptions
                .addOption(
                    "check.gradlewrapper.consistency",
                    "Verify gradle matches gradle wrapper version (exact, major, minor, off).",
                    "exact")
                .get()
                .toUpperCase(Locale.ROOT));

    if (optionValue == ConsistencyOptions.OFF) {
      return;
    }

    Properties wrapperProps = new Properties();
    {
      var wrapperProperties = project.file("gradle/wrapper/gradle-wrapper.properties");
      try (var reader =
          Files.newBufferedReader(wrapperProperties.toPath(), StandardCharsets.UTF_8)) {
        wrapperProps.load(reader);
      } catch (IOException e) {
        throw reportError(
            "environment-gradle-wrapper-unreadable",
            "Could not read the gradle-wrapper.properties file.",
            problemSpec -> {
              problemSpec.solution(
                  "Ensure gradle-wrapper.properties file is in the expected location at: "
                      + wrapperProperties.getAbsolutePath());
              problemSpec.withException(e);
            });
      }
    }

    String distributionUrl = wrapperProps.getProperty("distributionUrl");
    Pattern servicePattern =
        Pattern.compile(
            "(https://services.gradle.org/distributions/gradle-)(?<version>[^-]+)(-bin.zip)");
    Matcher m = servicePattern.matcher(distributionUrl);
    if (!m.find()) {
      throw reportError(
          "environment-distributionUrl-missing",
          "Could not find gradle version in the gradle-wrapper.properties 'distributionUrl'"
              + " property: ${distributionUrl}?",
          null);
    }

    String expectedGradleVersion = m.group("version");
    GradleVersion baseVersion = GradleVersion.current().getBaseVersion();
    GradleVersion expectedVersion = GradleVersion.version(expectedGradleVersion);

    boolean mismatch =
        switch (optionValue) {
          case OFF -> false;
          case EXACT -> baseVersion.compareTo(expectedVersion) != 0;
          case BASE -> {
            baseVersion = baseVersion.getBaseVersion();
            expectedVersion = expectedVersion.getBaseVersion();
            yield baseVersion.compareTo(expectedVersion) != 0;
          }
          case MAJOR -> baseVersion.getMajorVersion() != expectedVersion.getMajorVersion();
        };

    if (mismatch) {
      throw reportError(
          "environment-gradle-wrapper-mismatch",
          "Gradle version "
              + baseVersion
              + " does not match the one in gradle-wrapper.properties: "
              + expectedVersion,
          problemSpec -> {
            problemSpec.solution(
                "Use ./gradlew or .\\gradlew.bat scripts to use the project's gradle version ("
                    + expectedGradleVersion
                    + ").");
          });
    }
  }
}
