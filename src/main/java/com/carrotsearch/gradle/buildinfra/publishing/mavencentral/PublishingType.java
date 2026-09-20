package com.carrotsearch.gradle.buildinfra.publishing.mavencentral;

import java.util.Arrays;
import java.util.Locale;
import org.gradle.api.GradleException;

/** Central Portal's deployment publishing type. */
public enum PublishingType {
  /** The deployment is validated and then released to Maven Central automatically. */
  AUTOMATIC,

  /** The deployment is validated and then waits for a manual release (or drop) in the Portal. */
  USER_MANAGED;

  static PublishingType parse(String value) {
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new GradleException(
          String.format(
              Locale.ROOT,
              "Invalid publishing type '%s', expected one of: %s",
              value,
              Arrays.toString(values())));
    }
  }
}
