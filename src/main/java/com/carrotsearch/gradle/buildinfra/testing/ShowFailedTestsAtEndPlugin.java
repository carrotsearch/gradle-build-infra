package com.carrotsearch.gradle.buildinfra.testing;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;

/**
 * Prints all failed tests (across all projects), along with their output file and reproduce line,
 * at the end of the build.
 */
public class ShowFailedTestsAtEndPlugin extends AbstractPlugin {
  private static final String SERVICE_NAME = "failedTestsService";
  private static final int LIMIT = 10;

  @Inject
  public ShowFailedTestsAtEndPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    // Provides the reproduce line and test outputs extensions on test tasks.
    project.getPlugins().apply(TestingEnvPlugin.class);

    Provider<FailedTestsService> service =
        project
            .getGradle()
            .getSharedServices()
            .registerIfAbsent(SERVICE_NAME, FailedTestsService.class, spec -> {});

    project
        .getPlugins()
        .withType(
            JavaBasePlugin.class,
            plugin -> {
              project
                  .getTasks()
                  .withType(Test.class)
                  .configureEach(
                      task -> {
                        task.usesService(service);
                        String taskPath = task.getPath();

                        var reproduceLine =
                            task.getExtensions().getByType(ReproduceLineExtension.class);
                        File outputsDir =
                            task.getExtensions()
                                .getByType(TestOutputsExtension.class)
                                .getOutputsDir()
                                .get()
                                .getAsFile();

                        task.addTestListener(
                            new TestListener() {
                              @Override
                              public void beforeSuite(TestDescriptor suite) {}

                              @Override
                              public void afterSuite(TestDescriptor suite, TestResult result) {
                                // Failures outside of tests (initialization, before/after hooks).
                                if (TestNames.isClassSuite(suite)
                                    && result.getExceptions() != null
                                    && !result.getExceptions().isEmpty()) {
                                  service
                                      .get()
                                      .addFailedTest(
                                          new Entry(
                                              suite.getClassName(),
                                              taskPath,
                                              reproduceLine.getGradleReproLine(suite),
                                              outputFile(outputsDir, suite)));
                                }
                              }

                              @Override
                              public void beforeTest(TestDescriptor testDescriptor) {}

                              @Override
                              public void afterTest(TestDescriptor test, TestResult result) {
                                if (result.getResultType() == TestResult.ResultType.FAILURE) {
                                  String name = TestNames.displayName(test);
                                  if (test.getClassName() != null) {
                                    name = test.getClassName() + "." + name;
                                  }
                                  service
                                      .get()
                                      .addFailedTest(
                                          new Entry(
                                              name,
                                              taskPath,
                                              reproduceLine.getGradleReproLine(test),
                                              outputFile(
                                                  outputsDir,
                                                  test.getParent() != null
                                                      ? test.getParent()
                                                      : test)));
                                }
                              }
                            });
                      });
            });
  }

  private static File outputFile(File outputsDir, TestDescriptor suite) {
    return new File(outputsDir, ErrorReportingTestListener.getOutputLogName(suite));
  }

  /** A failed test or suite. */
  public record Entry(String name, String taskPath, String reproLine, File testOutput) {}

  /** Collects failed tests and prints them when closed (at the end of the build). */
  public abstract static class FailedTestsService
      implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(FailedTestsService.class);

    /** A set: a failure may be reported both for a test and for its suite. */
    private final Set<Entry> failedTests = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void addFailedTest(Entry entry) {
      failedTests.add(entry);
    }

    @Override
    public void close() {
      if (failedTests.isEmpty()) {
        return;
      }

      int count = failedTests.size();
      String formatted =
          failedTests.stream()
              .sorted(Comparator.comparing(Entry::taskPath).thenComparing(Entry::name))
              .limit(LIMIT)
              .map(
                  e ->
                      String.format(
                          Locale.ROOT,
                          "  - %s (%s)\n    Test output: %s\n    Reproduce with: %s\n",
                          e.name(),
                          e.taskPath(),
                          e.testOutput(),
                          e.reproLine()))
              .collect(Collectors.joining("\n"));

      LOGGER.error(
          "\nERROR: {} {} failed{}:\n\n{}",
          count,
          count == 1 ? "test has" : "tests have",
          count > LIMIT ? " (top " + LIMIT + " shown)" : "",
          formatted);
    }
  }
}
