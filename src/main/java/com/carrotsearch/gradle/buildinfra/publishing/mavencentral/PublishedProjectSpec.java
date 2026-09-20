package com.carrotsearch.gradle.buildinfra.publishing.mavencentral;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.Action;
import org.gradle.api.publish.maven.MavenPom;

/** Per-project publishing customizations. */
public abstract class PublishedProjectSpec {
  private final List<Action<? super MavenPom>> pomActions = new ArrayList<>();

  /** Configures the POM of this project. Applied after the POM configuration shared by all. */
  public void pom(Action<? super MavenPom> action) {
    pomActions.add(action);
  }

  List<Action<? super MavenPom>> getPomActions() {
    return pomActions;
  }
}
