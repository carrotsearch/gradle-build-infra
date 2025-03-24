package com.carrotsearch.gradle.buildinfra.conventions;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.diffplug.gradle.spotless.GroovyGradleExtension;
import com.diffplug.gradle.spotless.JavaExtension;
import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import com.diffplug.spotless.FormatterFunc;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;

public class ApplySpotlessFormattingPlugin extends AbstractPlugin {
  @Inject
  public ApplySpotlessFormattingPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(SpotlessPlugin.class);

    SpotlessExtension spotlessExtension =
        project.getExtensions().getByType(SpotlessExtension.class);

    connectToConventionTasks(project);

    // Get google java format version.
    var gjfVersionString = getGoogleJavaFormatVersion(project.getRootProject());

    // Apply gradle script formatting to all projects.
    applyGradleScriptsFormatting(project, spotlessExtension);

    // Apply java formatting settings to all java projects.
    var licenseHeaderFile =
        project
            .getObjects()
            .fileProperty()
            .convention(
                project
                    .getRootProject()
                    .getLayout()
                    .getProjectDirectory()
                    .file("gradle/spotless/license-header.txt"));

    project
        .getPlugins()
        .withType(
            JavaBasePlugin.class,
            plugin -> {
              spotlessExtension.java(
                  java -> {
                    applyJavaFormatterSettings(java, licenseHeaderFile, gjfVersionString);
                  });
            });
  }

  private void connectToConventionTasks(Project project) {
    project
        .getTasks()
        .named("tidy")
        .configure(
            task -> {
              task.dependsOn("spotlessApply");
            });

    project
        .getTasks()
        .named("check")
        .configure(
            task -> {
              task.dependsOn("spotlessCheck");
            });
  }

  private void applyGradleScriptsFormatting(Project project, SpotlessExtension spotlessExtension) {
    // Add an extra format to cover buildinfra's sources.
    var isRootProject = project == project.getRootProject();

    spotlessExtension.format(
        "gradleGroovy",
        GroovyGradleExtension.class,
        gradle -> {
          gradle.greclipse();
          gradle.leadingTabsToSpaces(2);
          if (isRootProject) {
            gradle.target("*.gradle", "gradle/**/*.gradle");
          } else {
            gradle.target("*.gradle");
          }
        });
  }

  private String getGoogleJavaFormatVersion(Project rootProject) {
    VersionCatalogsExtension versionCatalogsExtension =
        rootProject.getExtensions().findByType(VersionCatalogsExtension.class);

    if (versionCatalogsExtension == null) {
      throw reportError(
          "version-catalogs-missing",
          "VersionCatalogsExtension missing in project: " + rootProject.getPath());
    }

    Optional<VersionConstraint> gjfVersion =
        versionCatalogsExtension.named("libs").findVersion("googleJavaFormat");

    if (gjfVersion.isEmpty()) {
      throw reportError(
          "conventions-google-java-format-missing",
          "googleJavaFormat version must be declared in the 'versions' section of versions.toml");
    }

    return gjfVersion.get().toString();
  }

  private void applyJavaFormatterSettings(
      JavaExtension java, RegularFileProperty licenseHeader, String googleVecVersion) {

    if (licenseHeader.isPresent()) {
      java.licenseHeaderFile(licenseHeader);
    }

    java.encoding("UTF-8");
    java.importOrder();
    java.removeUnusedImports();
    java.trimTrailingWhitespace();
    java.endWithNewline();

    // Idea from: https://github.com/opensearch-project/opensearch-java/pull/1180/files
    java.custom(
        "Refuse wildcard imports",
        new FormatterFunc() {
          private final Pattern wildcardImport =
              Pattern.compile("(^import)(\\s+)(?:static\\s*)?([^*\\s]+\\.\\*;)", Pattern.MULTILINE);

          @Override
          public String apply(String input) throws Exception {
            Matcher matcher = wildcardImport.matcher(input);
            ArrayList<String> matches = new ArrayList<>();
            while (matcher.find()) {
              matches.add(matcher.group());
            }

            if (matches.isEmpty()) {
              return input;
            }

            throw new AssertionError(
                "Replace wildcard imports with explicit imports (spotless can't fix it):\n"
                    + matches.stream().map(e -> "  - " + e).collect(Collectors.joining("\n")));
          }
        });

    var gjf = java.googleJavaFormat(googleVecVersion);
    gjf.formatJavadoc(true);
    gjf.reorderImports(true);
    gjf.reflowLongStrings(false);
  }
}
