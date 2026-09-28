package com.carrotsearch.gradle.buildinfra.testing;

import org.gradle.api.file.DirectoryProperty;

/** Per-test-task extension pointing at the directory where outputs of failed suites are saved. */
public abstract class TestOutputsExtension {
  public static final String NAME = "testOutputs";

  public abstract DirectoryProperty getOutputsDir();
}
