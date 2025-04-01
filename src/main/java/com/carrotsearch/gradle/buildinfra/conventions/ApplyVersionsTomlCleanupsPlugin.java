package com.carrotsearch.gradle.buildinfra.conventions;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import javax.inject.Inject;
import nl.littlerobots.vcu.plugin.VersionCatalogUpdateExtension;
import nl.littlerobots.vcu.plugin.VersionCatalogUpdateTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.problems.Problems;
import org.gradle.api.tasks.TaskProvider;

public class ApplyVersionsTomlCleanupsPlugin extends AbstractPlugin {

  @Inject
  public ApplyVersionsTomlCleanupsPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    if (project.getRootProject() != project) {
      return;
    }

    project.getPlugins().apply(com.github.benmanes.gradle.versions.VersionsPlugin.class);
    project.getPlugins().apply(nl.littlerobots.vcu.plugin.VersionCatalogUpdatePlugin.class);

    // configure version catalog extension.
    var ext =
        project
            .getExtensions()
            .getByType(nl.littlerobots.vcu.plugin.VersionCatalogUpdateExtension.class);

    ext.getSortByKey().set(false);

    keepVersionsOfBuildInfraReferences(project, ext);

    TaskProvider<VersionCatalogUpdateTask> updateTask =
        project
            .getTasks()
            .withType(VersionCatalogUpdateTask.class)
            .named("versionCatalogUpdateLibs");

    updateTask.configure(
        task -> {
          task.setInteractive(true);
        });

    project
        .getTasks()
        .register(
            "updateVersions",
            task -> {
              task.dependsOn(updateTask);
            });
  }

  private void keepVersionsOfBuildInfraReferences(
      Project project, VersionCatalogUpdateExtension ext) {
    VersionCatalog libsCatalog = super.getLibsCatalog(project);

    var libsCatalogConfig = ext.getVersionCatalogs().create(libsCatalog.getName());
    libsCatalogConfig
        .getCatalogFile()
        .set(project.getLayout().getProjectDirectory().file("versions.toml").getAsFile());

    libsCatalogConfig.getKeep().getVersions().addAll("minJava", "googleJavaFormat");
  }
}
