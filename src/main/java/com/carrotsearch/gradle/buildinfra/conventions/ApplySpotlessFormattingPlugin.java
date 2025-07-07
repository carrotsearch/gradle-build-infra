package com.carrotsearch.gradle.buildinfra.conventions;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsExtension;
import com.diffplug.gradle.spotless.GroovyGradleExtension;
import com.diffplug.gradle.spotless.JavaExtension;
import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import com.diffplug.spotless.FormatterStep;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.tasks.TaskContainer;
import org.jetbrains.annotations.Nullable;

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
    var licenseHeaderFile = project.getObjects().fileProperty();

    var licenseFile =
        project
            .getRootProject()
            .getLayout()
            .getProjectDirectory()
            .file("gradle/spotless/license-header.txt");

    if (licenseFile.getAsFile().exists()) {
      licenseHeaderFile.convention(licenseFile);
    }

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
    // Add an extra format to cover groovy/gradle sources.
    var isRootProject = project == project.getRootProject();

    var spotlessGradleScriptsOptionName = "buildinfra.spotlessGradleGroovyScripts";
    var spotlessGradleScriptsOption =
        project
            .getExtensions()
            .getByType(BuildOptionsExtension.class)
            .addBooleanOption(
                spotlessGradleScriptsOptionName,
                "Enable formatting and validation of groovy/gradle scripts (you may want to turn it off if you "
                    + "don't work with gradle/groovy scripts.",
                true);

    if (spotlessGradleScriptsOption.get()) {
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
    } else {
      if (isRootProject) {
        project
            .getTasks()
            .register(
                "spotlessGradleGroovyIsOff",
                task -> {
                  task.doFirst(
                      t -> {
                        t.getLogger()
                            .warn(
                                "Spotless is turned off for gradle/groovy scripts. CI checks"
                                    + " may not pass if you have formatting differences"
                                    + " (enable '{}' build option to check and apply gradle/groovy formatting).",
                                spotlessGradleScriptsOptionName);
                      });
                });
      }

      List.of("spotlessGradleGroovy", "spotlessGradleGroovyApply", "spotlessGradleGroovyCheck")
          .forEach(
              taskName -> {
                TaskContainer tasks = project.getTasks();
                tasks.register(
                    taskName,
                    task -> {
                      task.dependsOn(":spotlessGradleGroovyIsOff");
                    });
              });
    }

    project
        .getTasks()
        .named("tidy")
        .configure(
            t -> {
              t.dependsOn("spotlessGradleGroovyApply");
            });

    project
        .getTasks()
        .named("check")
        .configure(
            t -> {
              t.dependsOn("spotlessGradleGroovyCheck");
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

  public static class WildcardImportDetectorStep implements Serializable, FormatterStep {
    private final Pattern wildcardImport =
        Pattern.compile("(^import)(\\s+)(?:static\\s*)?([^*\\s]+\\.\\*;)", Pattern.MULTILINE);

    @Override
    public String getName() {
      return "Custom wildcard import detector";
    }

    @Override
    public @Nullable String format(String rawUnix, File file) throws Exception {
      Matcher matcher = wildcardImport.matcher(rawUnix);
      ArrayList<String> matches = new ArrayList<>();
      while (matcher.find()) {
        matches.add(matcher.group());
      }

      if (matches.isEmpty()) {
        return rawUnix;
      }

      throw new AssertionError(
          "Replace static imports with explicit imports (spotless can't fix it): "
              + matches.iterator().next());
    }

    @Override
    public void close() throws Exception {
      // Do nothing.
    }
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
    java.addStep(new WildcardImportDetectorStep());

    var gjf = java.googleJavaFormat(googleVecVersion);
    gjf.formatJavadoc(true);
    gjf.reorderImports(true);
    gjf.reflowLongStrings(false);
  }
}
