package com.carrotsearch.gradle.buildinfra.buildoptions;

public record BuildOptionValue(String value, BuildOptionValueSource source) {
  @Override
  public String toString() {
    return value;
  }
}
