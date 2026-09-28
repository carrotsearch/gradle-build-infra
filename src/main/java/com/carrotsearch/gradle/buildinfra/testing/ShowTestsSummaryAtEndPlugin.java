package com.carrotsearch.gradle.buildinfra.testing;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import java.util.concurrent.ConcurrentLinkedQueue;
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

/** Prints a summary of all executed test tasks (across all projects) at the end of the build. */
public class ShowTestsSummaryAtEndPlugin extends AbstractPlugin {
  private static final String SERVICE_NAME = "testsSummaryService";

  @Inject
  public ShowTestsSummaryAtEndPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    Provider<TestsSummaryService> service =
        project
            .getGradle()
            .getSharedServices()
            .registerIfAbsent(SERVICE_NAME, TestsSummaryService.class, spec -> {});

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
                        task.addTestListener(
                            new TestListener() {
                              @Override
                              public void beforeSuite(TestDescriptor suite) {}

                              @Override
                              public void afterSuite(TestDescriptor suite, TestResult result) {
                                // The root suite carries the totals for the whole task.
                                if (suite.getParent() == null) {
                                  service
                                      .get()
                                      .addTaskResult(
                                          new TaskResult(
                                              result.getTestCount(),
                                              result.getFailedTestCount(),
                                              result.getSkippedTestCount()));
                                }
                              }

                              @Override
                              public void beforeTest(TestDescriptor testDescriptor) {}

                              @Override
                              public void afterTest(
                                  TestDescriptor testDescriptor, TestResult result) {}
                            });
                      });
            });
  }

  /** Results of a single test task. */
  public record TaskResult(long tests, long failures, long skipped) {}

  /** Collects test task results and prints the summary when closed (at the end of the build). */
  public abstract static class TestsSummaryService
      implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(TestsSummaryService.class);

    private final ConcurrentLinkedQueue<TaskResult> taskResults = new ConcurrentLinkedQueue<>();

    public void addTaskResult(TaskResult result) {
      taskResults.add(result);
    }

    @Override
    public void close() {
      if (taskResults.isEmpty()) {
        return;
      }

      long tests = 0, failures = 0, skipped = 0;
      for (TaskResult r : taskResults) {
        tests += r.tests();
        failures += r.failures();
        skipped += r.skipped();
      }

      StringBuilder msg = new StringBuilder();
      msg.append(pluralize("test task", taskResults.size()))
          .append(" executed, ")
          .append(pluralize("test", tests));
      if (failures > 0) {
        msg.append(", ").append(pluralize("failure", failures));
      }
      if (skipped > 0) {
        msg.append(", ").append(skipped).append(" ignored");
      }
      LOGGER.lifecycle("{}\n", msg);
    }

    private static String pluralize(String word, long count) {
      return count + " " + (count == 1 ? word : word + "s");
    }
  }
}
