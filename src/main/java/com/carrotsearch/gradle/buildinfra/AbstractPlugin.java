package com.carrotsearch.gradle.buildinfra;

import java.util.Optional;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
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
          "Theis plugin is applicable to the rootProject only (currently applied to "
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
        problemSpec -> {
          problemSpec
              .id(id, label)
              .contextualLabel(label)
              .severity(Severity.ERROR)
              .withException(new GradleException());
          if (action != null) {
            action.execute(problemSpec);
          }
        });
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
