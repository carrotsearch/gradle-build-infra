package buildinfra.environment;

import buildinfra.AbstractPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

  @Override
  public void apply(Project project) {
    super.pluginAppliedToRootProject(project);

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
    if (GradleVersion.current()
            .getBaseVersion()
            .compareTo(GradleVersion.version(expectedGradleVersion))
        != 0) {
      throw reportError(
          "environment-gradle-wrapper-mismatch",
          "Gradle version does not match the one in gradle-wrapper.properties.",
          problemSpec -> {
            problemSpec.solution(
                "Use ./gradlew or .\\gradlew.bat scripts to use the project's gradle version ("
                    + expectedGradleVersion
                    + ").");
          });
    }
  }
}
