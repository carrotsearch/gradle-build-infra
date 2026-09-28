package com.carrotsearch.gradle.buildinfra.testing;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsExtension;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin;
import com.carrotsearch.gradle.buildinfra.utils.DurationTable;
import com.carrotsearch.gradle.buildinfra.utils.Units;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFailureResult;

/** Prints the slowest tests and test suites (across all projects) at the end of the build. */
public class ShowSlowestTestsAtEndPlugin extends AbstractPlugin {
  public static final String OPT_SLOWEST_TESTS = "tests.slowestTests";
  public static final String OPT_SLOWEST_TESTS_MIN_TIME = "tests.slowestTests.minTime";
  public static final String OPT_SLOWEST_SUITES = "tests.slowestSuites";
  public static final String OPT_SLOWEST_SUITES_MIN_TIME = "tests.slowestSuites.minTime";

  private static final String SERVICE_NAME = "testStatsService";
  private static final int LIMIT = 10;

  private final BuildEventsListenerRegistry listenerRegistry;

  @Inject
  public ShowSlowestTestsAtEndPlugin(
      Problems problems, BuildEventsListenerRegistry listenerRegistry) {
    super(problems);
    this.listenerRegistry = listenerRegistry;
  }

  @Override
  public void apply(Project project) {
    Provider<TestStatsService> service;
    if (isRootProject(project)) {
      service = registerService(project);
      listenerRegistry.onTaskCompletion(service);
    } else {
      // The root project declares the options and registers the service.
      project.getRootProject().getPlugins().apply(ShowSlowestTestsAtEndPlugin.class);
      service =
          project
              .getGradle()
              .getSharedServices()
              .registerIfAbsent(SERVICE_NAME, TestStatsService.class, spec -> {});
    }

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
                        task.addTestListener(
                            new TestListener() {
                              @Override
                              public void beforeSuite(TestDescriptor suite) {}

                              @Override
                              public void afterSuite(TestDescriptor suite, TestResult result) {
                                if (TestNames.isClassSuite(suite)) {
                                  service
                                      .get()
                                      .addSuite(
                                          new Entry(
                                              simpleName(suite.getClassName())
                                                  + " ("
                                                  + taskPath
                                                  + ")",
                                              result.getEndTime() - result.getStartTime()));
                                }
                              }

                              @Override
                              public void beforeTest(TestDescriptor testDescriptor) {}

                              @Override
                              public void afterTest(TestDescriptor test, TestResult result) {
                                String name = TestNames.displayName(test);
                                if (test.getClassName() != null) {
                                  name = simpleName(test.getClassName()) + "." + name;
                                }
                                service
                                    .get()
                                    .addTest(
                                        new Entry(
                                            name + " (" + taskPath + ")",
                                            result.getEndTime() - result.getStartTime()));
                              }
                            });
                      });
            });
  }

  private Provider<TestStatsService> registerService(Project rootProject) {
    rootProject.getPlugins().apply(BuildOptionsPlugin.class);
    var buildOptions = rootProject.getExtensions().getByType(BuildOptionsExtension.class);

    var slowestTests =
        buildOptions.addBooleanOption(
            OPT_SLOWEST_TESTS, "Print the summary of the slowest tests.", true);
    var testsMinTime =
        buildOptions.addIntOption(
            OPT_SLOWEST_TESTS_MIN_TIME, "Minimum test time to consider a test slow (millis).", 500);
    var slowestSuites =
        buildOptions.addBooleanOption(
            OPT_SLOWEST_SUITES, "Print the summary of the slowest suites.", true);
    var suitesMinTime =
        buildOptions.addIntOption(
            OPT_SLOWEST_SUITES_MIN_TIME,
            "Minimum suite time to consider a suite slow (millis).",
            1000);

    return rootProject
        .getGradle()
        .getSharedServices()
        .registerIfAbsent(
            SERVICE_NAME,
            TestStatsService.class,
            spec ->
                spec.parameters(
                    params -> {
                      params.getSlowestTests().set(slowestTests.get());
                      params.getTestsMinTime().set(testsMinTime.get());
                      params.getSlowestSuites().set(slowestSuites.get());
                      params.getSuitesMinTime().set(suitesMinTime.get());
                    }));
  }

  private static String simpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  /** A test or suite with its wall-clock duration. */
  public record Entry(String name, long durationMillis) {}

  /**
   * Collects test and suite times and prints the slowest ones when closed (at the end of a
   * successful build).
   */
  public abstract static class TestStatsService
      implements BuildService<TestStatsServiceParams>, OperationCompletionListener, AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(TestStatsService.class);

    private final ConcurrentLinkedQueue<Entry> tests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Entry> suites = new ConcurrentLinkedQueue<>();

    private volatile boolean hadFailedTask;

    public void addTest(Entry entry) {
      if (getParameters().getSlowestTests().get()) {
        tests.add(entry);
      }
    }

    public void addSuite(Entry entry) {
      if (getParameters().getSlowestSuites().get()) {
        suites.add(entry);
      }
    }

    @Override
    public void onFinish(FinishEvent event) {
      if (event.getResult() instanceof TaskFailureResult) {
        hadFailedTask = true;
      }
    }

    @Override
    public void close() {
      if (hadFailedTask) {
        return;
      }

      var params = getParameters();
      report("The slowest tests during this run:", tests, params.getTestsMinTime().get());
      report("The slowest suites during this run:", suites, params.getSuitesMinTime().get());
    }

    private static void report(String header, Collection<Entry> entries, long minTimeMillis) {
      List<DurationTable.Row> rows =
          entries.stream()
              .filter(e -> e.durationMillis() >= minTimeMillis)
              .sorted(Comparator.comparingLong(Entry::durationMillis).reversed())
              .limit(LIMIT)
              .map(e -> new DurationTable.Row(e.durationMillis(), e.name()))
              .toList();
      if (rows.isEmpty()) {
        return;
      }

      LOGGER.lifecycle("{}\n{}\n", header, DurationTable.format(Units.DURATION_COMPACT, rows));
    }
  }

  public interface TestStatsServiceParams extends BuildServiceParameters {
    Property<Boolean> getSlowestTests();

    Property<Integer> getTestsMinTime();

    Property<Boolean> getSlowestSuites();

    Property<Integer> getSuitesMinTime();
  }
}
