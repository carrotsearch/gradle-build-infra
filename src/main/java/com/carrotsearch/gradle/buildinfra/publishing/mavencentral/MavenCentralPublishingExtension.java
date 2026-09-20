package com.carrotsearch.gradle.buildinfra.publishing.mavencentral;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.publish.maven.MavenPom;

public abstract class MavenCentralPublishingExtension {
  public static final String NAME = "mavenCentralPublishing";

  private final ObjectFactory objects;
  private final DomainObjectSet<String> projects;
  private final List<Action<? super MavenPom>> pomActions = new ArrayList<>();
  private final Map<String, PublishedProjectSpec> projectSpecs = new LinkedHashMap<>();

  @Inject
  public MavenCentralPublishingExtension(ObjectFactory objects) {
    this.objects = objects;
    this.projects = objects.domainObjectSet(String.class);
  }

  /** Paths of projects to be published (":" is the root project). */
  public DomainObjectSet<String> getProjects() {
    return projects;
  }

  public void setProjects(Iterable<String> projectPaths) {
    projectPaths.forEach(projects::add);
  }

  public void projects(String... projectPaths) {
    setProjects(List.of(projectPaths));
  }

  /** Adds a project to be published, with project-specific customizations. */
  public void project(String projectPath, Action<? super PublishedProjectSpec> action) {
    action.execute(projectSpec(projectPath));
    projects.add(projectPath);
  }

  /** Configures the POM shared by all published projects. */
  public void pom(Action<? super MavenPom> action) {
    pomActions.add(action);
  }

  /**
   * The name used for the bundle file and for the deployment (suffixed with the version). Defaults
   * to the root project's name.
   */
  public abstract Property<String> getBundleName();

  /**
   * {@code AUTOMATIC} (the default) or {@code USER_MANAGED}.
   *
   * @see PublishingType
   */
  public abstract Property<String> getPublishingType();

  /**
   * How long to wait for the uploaded deployment to pass validation. {@link Duration#ZERO} means
   * don't wait at all.
   */
  public abstract Property<Duration> getValidationTimeout();

  /** The delay between deployment status checks. */
  public abstract Property<Duration> getStatusPollInterval();

  /** Require a clean git checkout for publishing releases. */
  public abstract Property<Boolean> getRequireCleanCheckout();

  /** Generate and publish Gradle Module Metadata ({@code *.module} files). */
  public abstract Property<Boolean> getGradleModuleMetadata();

  /**
   * Checksum types to include in the bundle. {@code md5} and {@code sha1} are required by Maven
   * Central, {@code sha256} and {@code sha512} are optional.
   */
  public abstract SetProperty<String> getBundleChecksums();

  /** Central Portal user token's name. */
  public abstract Property<String> getUsername();

  /** Central Portal user token's password. */
  public abstract Property<String> getPassword();

  public abstract Property<String> getPortalUrl();

  public abstract Property<String> getSnapshotsUrl();

  List<Action<? super MavenPom>> getPomActions() {
    return pomActions;
  }

  PublishedProjectSpec projectSpec(String projectPath) {
    return projectSpecs.computeIfAbsent(
        projectPath, path -> objects.newInstance(PublishedProjectSpec.class));
  }
}
