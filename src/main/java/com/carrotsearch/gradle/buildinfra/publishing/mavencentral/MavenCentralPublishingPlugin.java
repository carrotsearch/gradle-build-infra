package com.carrotsearch.gradle.buildinfra.publishing.mavencentral;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.environment.GitInfoExtension;
import com.carrotsearch.gradle.buildinfra.environment.GitInfoPlugin;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.plugins.signing.SigningExtension;

/**
 * Publishes selected projects to Maven Central: releases are uploaded as a single bundle via
 * Sonatype Central Portal's publisher API, snapshots are published directly to Central Portal's
 * snapshot repository.
 */
public class MavenCentralPublishingPlugin extends AbstractPlugin {
  public static final String TASK_GROUP = "Publishing";

  public static final String BUNDLE_REPOSITORY = "CentralBundle";
  public static final String SNAPSHOTS_REPOSITORY = "CentralSnapshots";
  public static final String PUBLICATION = "jars";

  public static final String TASK_CLEAN_LOCAL = "mavenLocalClean";
  public static final String TASK_PUBLISH_LOCAL = "publishLocal";
  public static final String TASK_CHECK_CLEAN_CHECKOUT = "checkCleanCheckout";
  public static final String TASK_PREPARE_BUNDLE = "prepareMavenCentralBundle";
  public static final String TASK_UPLOAD_BUNDLE = "uploadMavenCentralBundle";
  public static final String TASK_PUBLISH = "publishToMavenCentral";

  private static final Set<String> CHECKSUM_TYPES = Set.of("md5", "sha1", "sha256", "sha512");

  @Inject
  public MavenCentralPublishingPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project rootProject) {
    super.pluginAppliedToRootProject(rootProject);

    rootProject.getPlugins().apply(GitInfoPlugin.class);
    var gitInfo = rootProject.getExtensions().getByType(GitInfoExtension.class).getGitInfo();

    var providers = rootProject.getProviders();
    var ext =
        rootProject
            .getExtensions()
            .create(MavenCentralPublishingExtension.NAME, MavenCentralPublishingExtension.class);

    ext.getBundleName().convention(rootProject.getName());
    ext.getPublishingType().convention(PublishingType.AUTOMATIC.name());
    ext.getValidationTimeout().convention(Duration.ofMinutes(30));
    ext.getStatusPollInterval().convention(Duration.ofSeconds(5));
    ext.getRequireCleanCheckout().convention(true);
    ext.getGradleModuleMetadata().convention(true);
    ext.getBundleChecksums().convention(List.of("md5", "sha1"));
    ext.getUsername()
        .convention(
            providers
                .gradleProperty("mavenCentralUsername")
                .orElse(providers.gradleProperty("nexusUsername")));
    ext.getPassword()
        .convention(
            providers
                .gradleProperty("mavenCentralPassword")
                .orElse(providers.gradleProperty("nexusPassword")));
    ext.getPortalUrl().convention("https://central.sonatype.com");
    ext.getSnapshotsUrl().convention("https://central.sonatype.com/repository/maven-snapshots/");

    Provider<String> version = providers.provider(() -> rootProject.getVersion().toString());
    Provider<Boolean> snapshotBuild = version.map(v -> v.endsWith("-SNAPSHOT"));
    Provider<String> versionedName = ext.getBundleName().zip(version, (n, v) -> n + "-" + v);

    Provider<Directory> bundleRepositoryDir =
        rootProject.getLayout().getBuildDirectory().dir("maven");

    var tasks = rootProject.getTasks();

    tasks.register(
        TASK_CLEAN_LOCAL,
        Delete.class,
        task -> {
          task.delete(bundleRepositoryDir);
        });

    // Task paths of the published projects, collected as projects are added to the extension.
    List<String> publishLocalTasks = new ArrayList<>();
    List<String> publishSnapshotTasks = new ArrayList<>();

    var publishLocal =
        tasks.register(
            TASK_PUBLISH_LOCAL,
            task -> {
              task.setGroup(TASK_GROUP);
              task.setDescription(
                  "Publish Maven artifacts locally to " + bundleRepositoryDir.get().getAsFile());
              task.dependsOn(publishLocalTasks);
            });

    var checkCleanCheckout =
        tasks.register(
            TASK_CHECK_CLEAN_CHECKOUT,
            CheckCleanCheckout.class,
            task -> {
              task.getGitInfo().set(gitInfo);

              var requireCleanCheckout = ext.getRequireCleanCheckout();
              task.onlyIf("Clean checkout is required", t -> requireCleanCheckout.get());
            });

    var prepareBundle =
        tasks.register(
            TASK_PREPARE_BUNDLE,
            Zip.class,
            task -> {
              task.setGroup(TASK_GROUP);
              task.setDescription("Prepare a bundle of Maven artifacts for Central Portal.");
              task.dependsOn(publishLocal);

              task.getArchiveFileName().set(versionedName.map(n -> "bundle-" + n + ".zip"));
              task.getDestinationDirectory()
                  .set(rootProject.getLayout().getBuildDirectory().dir("maven-bundle"));

              task.from(bundleRepositoryDir);

              // Central Portal doesn't recognize bundles with maven-metadata files in them.
              task.exclude("**/maven-metadata*");

              // Filter out unwanted checksums (and checksums of signatures, which older
              // gradle versions generate).
              var bundleChecksums = ext.getBundleChecksums();
              task.getInputs().property("bundleChecksums", bundleChecksums);
              task.exclude(
                  element -> {
                    String name = element.getName();
                    String extension = name.substring(name.lastIndexOf('.') + 1);
                    return CHECKSUM_TYPES.contains(extension)
                        && (name.endsWith(".asc." + extension)
                            || !bundleChecksums.get().contains(extension));
                  });
            });

    var uploadBundle =
        tasks.register(
            TASK_UPLOAD_BUNDLE,
            UploadBundleToCentralPortal.class,
            task -> {
              task.setDescription("Upload the bundle of Maven artifacts to Central Portal.");
              task.dependsOn(checkCleanCheckout);

              task.getBundleFile().set(prepareBundle.flatMap(Zip::getArchiveFile));
              task.getDeploymentName().set(versionedName);
              task.getPublishingType().set(ext.getPublishingType());
              task.getPortalUrl().set(ext.getPortalUrl());
              task.getUsername().set(ext.getUsername());
              task.getPassword().set(ext.getPassword());
              task.getValidationTimeout().set(ext.getValidationTimeout());
              task.getStatusPollInterval().set(ext.getStatusPollInterval());

              task.getSnapshotVersion().set(snapshotBuild);
            });

    // Don't even start publishing artifacts locally if the checkout is not clean.
    publishLocal.configure(task -> task.mustRunAfter(checkCleanCheckout));

    tasks.register(
        TASK_PUBLISH,
        task -> {
          task.setGroup(TASK_GROUP);
          task.setDescription(
              "Publish Maven artifacts to Maven Central (a bundle upload to Central Portal for"
                  + " releases, snapshot repository for snapshots)");

          task.dependsOn(
              snapshotBuild.map(
                  snapshot -> snapshot ? publishSnapshotTasks : List.of(uploadBundle)));
        });

    ext.getProjects()
        .all(
            projectPath -> {
              Project project = rootProject.project(projectPath);
              publishLocalTasks.add(
                  taskPath(project, "publishAllPublicationsTo" + BUNDLE_REPOSITORY + "Repository"));
              publishSnapshotTasks.add(
                  taskPath(
                      project, "publishAllPublicationsTo" + SNAPSHOTS_REPOSITORY + "Repository"));
              configurePublishedProject(project, ext, bundleRepositoryDir, snapshotBuild);
            });
  }

  private void configurePublishedProject(
      Project project,
      MavenCentralPublishingExtension ext,
      Provider<Directory> bundleRepositoryDir,
      Provider<Boolean> snapshotBuild) {
    var plugins = project.getPluginManager();
    plugins.apply("maven-publish");
    plugins.apply("signing");

    var publishing = project.getExtensions().getByType(PublishingExtension.class);
    var signing = project.getExtensions().getByType(SigningExtension.class);

    publishing
        .getRepositories()
        .maven(
            repo -> {
              repo.setName(BUNDLE_REPOSITORY);
              repo.setUrl(bundleRepositoryDir);
            });

    // The bundle must only contain what this build has published.
    String cleanLocal = ":" + TASK_CLEAN_LOCAL;
    project
        .getTasks()
        .matching(task -> task.getName().endsWith("To" + BUNDLE_REPOSITORY + "Repository"))
        .configureEach(task -> task.dependsOn(cleanLocal));

    plugins.withPlugin(
        "java",
        unused -> {
          var java = project.getExtensions().getByType(JavaPluginExtension.class);
          java.withSourcesJar();
          java.withJavadocJar();

          publishing
              .getPublications()
              .create(
                  PUBLICATION,
                  MavenPublication.class,
                  publication -> {
                    publication.from(project.getComponents().getByName("java"));
                  });
        });

    publishing.getPublications().withType(MavenPublication.class).all(signing::sign);

    // Anything that depends on the values set in the root project's extension (or in the
    // published project's build script) has to be deferred.
    whenEvaluated(
        project,
        unused -> {
          boolean snapshot = snapshotBuild.get();

          publishing
              .getRepositories()
              .maven(
                  repo -> {
                    repo.setName(SNAPSHOTS_REPOSITORY);
                    repo.setUrl(ext.getSnapshotsUrl().get());
                    if (ext.getUsername().isPresent() && ext.getPassword().isPresent()) {
                      repo.credentials(
                          credentials -> {
                            credentials.setUsername(ext.getUsername().get());
                            credentials.setPassword(ext.getPassword().get());
                          });
                    }
                  });

          signing.setRequired(!snapshot);
          var providers = project.getProviders();
          var signingKey = providers.gradleProperty("signingKey");
          if (signingKey.isPresent()) {
            signing.useInMemoryPgpKeys(
                providers.gradleProperty("signingKeyId").getOrNull(),
                signingKey.get(),
                providers.gradleProperty("signingPassword").getOrElse(""));
          }

          boolean gradleModuleMetadata = ext.getGradleModuleMetadata().get();
          project
              .getTasks()
              .withType(GenerateModuleMetadata.class)
              .configureEach(task -> task.setEnabled(gradleModuleMetadata));

          publishing
              .getPublications()
              .withType(MavenPublication.class)
              .all(
                  publication -> {
                    if (publication.getName().equals(PUBLICATION)) {
                      publication.setArtifactId(
                          project
                              .getExtensions()
                              .getByType(BasePluginExtension.class)
                              .getArchivesName()
                              .get());
                    }

                    publication.pom(
                        pom -> {
                          pom.getName().convention(project.getName());
                          pom.getDescription().convention(project.getDescription());
                          ext.getPomActions().forEach(action -> action.execute(pom));
                          ext.projectSpec(project.getPath())
                              .getPomActions()
                              .forEach(action -> action.execute(pom));
                        });
                  });
        });
  }

  private static void whenEvaluated(Project project, Action<? super Project> action) {
    if (project.getState().getExecuted()) {
      action.execute(project);
    } else {
      project.afterEvaluate(action);
    }
  }

  private static String taskPath(Project project, String taskName) {
    return project == project.getRootProject()
        ? ":" + taskName
        : project.getPath() + ":" + taskName;
  }
}
