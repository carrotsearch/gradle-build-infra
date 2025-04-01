package com.carrotsearch.gradle.buildinfra.conventions;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import javax.inject.Inject;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.problems.Problems;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

public class ApplySaneJavaDefaultsPlugin extends AbstractPlugin {

  @Inject
  public ApplySaneJavaDefaultsPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    project
        .getPlugins()
        .withType(
            JavaBasePlugin.class,
            unused -> {
              configureMinJavaVersion(project);
              configureUtf8(project);
            });
  }

  private void configureUtf8(Project project) {
    project
        .getTasks()
        .withType(JavaCompile.class)
        .configureEach(
            task -> {
              task.getOptions().setEncoding("UTF-8");
            });
    project
        .getTasks()
        .withType(Javadoc.class)
        .configureEach(
            task -> {
              task.getOptions().setEncoding("UTF-8");
            });
  }

  private void configureMinJavaVersion(Project project) {
    var libsCatalog = super.getLibsCatalog(project);
    var minJava = libsCatalog.findVersion("minJava");
    if (minJava.isEmpty()) {
      throw super.reportError(
          "missing-minJava-version",
          "Expected to see a 'minJava' version property in 'libs' catalog.");
    }

    var minJavaVersion = JavaVersion.toVersion(minJava.get());
    var javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
    javaExt.setSourceCompatibility(minJavaVersion);
    javaExt.setTargetCompatibility(minJavaVersion);
    javaExt
        .getToolchain()
        .getLanguageVersion()
        .set(JavaLanguageVersion.of(minJava.get().toString()));
  }
}
