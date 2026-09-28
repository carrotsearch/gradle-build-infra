package com.carrotsearch.gradle.buildinfra;

import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsExtension;
import java.util.Optional;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.problems.ProblemGroup;
import org.gradle.api.problems.ProblemId;
import org.gradle.api.problems.ProblemReporter;
import org.gradle.api.problems.ProblemSpec;
import org.gradle.api.problems.Problems;
import org.gradle.api.problems.Severity;

public abstract class AbstractPlugin implements Plugin<Project> {
  @SuppressWarnings("UnstableApiUsage")
  private final ProblemReporter problemReporter;

  public AbstractPlugin(Problems problems) {
    this.problemReporter = problems.getReporter();
  }

  protected final void pluginAppliedToRootProject(Project project) {
    if (!isRootProject(project)) {
      throw reportError(
          "environment-apply-root-project",
          "This plugin is applicable to the rootProject only (currently applied to "
              + project.getPath()
              + ")");
    }
  }

  protected static final boolean isRootProject(Project project) {
    return project == project.getRootProject();
  }

  protected RuntimeException reportError(String id, String label) {
    throw reportError(id, label, null);
  }

  protected RuntimeException reportError(String id, String label, Action<ProblemSpec> action) {
    throw problemReporter.throwing(
        new GradleException(),
        ProblemId.create(
            id, label, ProblemGroup.create("buildinfra", "Build infrastructure problems")),
        problemSpec -> {
          problemSpec
              .contextualLabel(label)
              .severity(Severity.ERROR)
              .withException(new GradleException());
          if (action != null) {
            action.execute(problemSpec);
          }
        });
  }

  /**
   * Declares a deprecated (renamed) build option so that all value sources are consulted, and fails
   * the build if a value is present, pointing at the replacement option.
   */
  protected final void failIfDeprecatedOptionUsed(
      BuildOptionsExtension buildOptions, String deprecatedName) {
    String replacement = DeprecatedBuildOptions.RENAMES.get(deprecatedName);
    if (replacement == null) {
      throw new IllegalArgumentException("Not a deprecated option: " + deprecatedName);
    }

    if (!buildOptions.hasOption(deprecatedName)) {
      buildOptions.addOption(deprecatedName, "Deprecated, renamed to '" + replacement + "'.");
    }

    if (buildOptions.getOption(deprecatedName).isPresent()) {
      throw reportError(
          "deprecated-build-option",
          "Build option '" + deprecatedName + "' has been renamed to '" + replacement + "'.",
          spec ->
              spec.solution(
                  "Rename the option in build-options.properties, gradle.properties, the"
                      + " environment or on the command line."));
    }
  }

  protected VersionCatalog getLibsCatalog(Project project) {
    VersionCatalogsExtension ext =
        project.getRootProject().getExtensions().findByType(VersionCatalogsExtension.class);

    Optional<VersionCatalog> libsCatalog;
    if (ext == null || (libsCatalog = ext.find("libs")).isEmpty()) {
      throw reportError(
          "conventions-libs-catalog-missing",
          "Expected to see a version catalog named 'libs' declared in project: "
              + project.getPath());
    }

    return libsCatalog.get();
  }
}
