package com.carrotsearch.gradle.buildinfra;

import javax.inject.Inject;
import org.gradle.api.provider.Property;
import org.gradle.process.ExecOperations;

public abstract class BuildInfraExtension {
  public static final String NAME = "buildinfra";

  /**
   * @return Returns a property indicating we're running under IntelliJ IDEA.
   */
  public abstract Property<Boolean> getIntelliJIdea();

  /**
   * @return Returns the {@code ExecOperations} implementation.
   */
  @Inject
  public abstract ExecOperations getExecOps();
}
