package com.carrotsearch.gradle.buildinfra.buildoptions;

public record BuildOptionValue(String value, boolean defaultValue, BuildOptionValueSource source) {
  @Override
  public String toString() {
    return value;
  }
}
