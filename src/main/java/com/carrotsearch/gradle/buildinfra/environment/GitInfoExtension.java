package com.carrotsearch.gradle.buildinfra.environment;

import org.gradle.api.provider.MapProperty;

public abstract class GitInfoExtension {
  public static final String NAME = "gitinfo";

  public abstract MapProperty<String, String> getGitInfo();
}
