package com.carrotsearch.gradle.buildinfra.testing;

import org.gradle.api.tasks.testing.TestDescriptor;

/** Helpers for naming tests in reports. */
final class TestNames {
  private TestNames() {}

  /**
   * The test's method name, usable in a {@code --tests} filter: JUnit Platform reports test names
   * with a parameter list (e.g. {@code testFoo()} or {@code testBar(String)[1]}), which is
   * stripped.
   */
  static String methodName(TestDescriptor descriptor) {
    return descriptor.getName().replaceAll("\\(.*$", "");
  }

  /**
   * The test's display name, without the parameter list JUnit Platform appends to method names
   * (e.g. {@code testFoo()} or {@code testBar(String)}). Custom display names are left as they are.
   */
  static String displayName(TestDescriptor descriptor) {
    return descriptor.getDisplayName().replaceAll("\\([^()]*\\)$", "");
  }

  /** True for the suite of a test class (as opposed to gradle's runners or nested containers). */
  static boolean isClassSuite(TestDescriptor suite) {
    return suite.getClassName() != null
        && (suite.getParent() == null || suite.getParent().getClassName() == null);
  }
}
