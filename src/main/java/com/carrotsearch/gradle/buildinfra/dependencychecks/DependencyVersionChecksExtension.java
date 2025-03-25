package com.carrotsearch.gradle.buildinfra.dependencychecks;

import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

public abstract class DependencyVersionChecksExtension {
  public static String EXTENSION_NAME = "dependencyVersionChecks";

  public abstract Property<String> getLockFileComment();

  @Nested
  public abstract NamedDomainObjectContainer<ConfigurationGroup> getConfigurationGroups();
}
