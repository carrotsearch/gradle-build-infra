package com.carrotsearch.gradle.buildinfra.testing;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsExtension;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.inject.Inject;
import org.apache.tools.ant.types.Commandline;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.configuration.BuildFeatures;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.internal.tasks.testing.logging.DefaultTestLogging;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.testing.*;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLogEvent;
import org.gradle.api.tasks.testing.logging.TestLoggingContainer;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.process.CommandLineArgumentProvider;
import org.jetbrains.annotations.NotNull;

public abstract class TestingEnvPlugin extends AbstractPlugin {
  private static final String TEST_OUTPUTS_DIR = "test-outputs";
  private static final String PRINT_RANDOMIZATION_SEED_INFO_TASK_NAME = "randomizationInfo";
  private static final String ALL_TESTS_SUMMARY_TASK_NAME = "allTestsSummary";

  class TestSummary implements Serializable {
    long testTasksExecuted;
    long tests;
    long failures;
    long ignored;

    public synchronized void incrementTasks() {
      testTasksExecuted++;
    }

    public synchronized void suiteResult(TestDescriptor desc, TestResult result) {
      if (desc.isComposite()) return;
      account(result);
    }

    public synchronized void testResult(TestDescriptor desc, TestResult result) {
      if (desc.isComposite()) return;
      account(result);
    }

    private void account(TestResult result) {
      tests += result.getTestCount();
      failures += result.getFailedTestCount();
      ignored += result.getSkippedTestCount();
    }
  }

  abstract static class RootTestingProjectExtension {
    public static final String NAME = "buildInfra-testing-root";

    public RootTestingProjectExtension() {}

    abstract Property<String> getRootSeed();

    abstract Property<TestSummary> getTestSummary();
  }

  abstract static class TestingProjectExtension {
    public static final String NAME = "buildInfra-testing";

    public TestingProjectExtension() {}

    TaskCollection<Test> getTestTasks(Project project) {
      return project.getTasks().withType(Test.class);
    }
  }

  @Inject
  protected abstract FileSystemOperations getFilesystemOps();

  @Inject
  protected abstract BuildFeatures getBuildFeatures();

  @Inject
  protected abstract StyledTextOutputFactory getStyledOutputFactory();

  @Inject
  public TestingEnvPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    if (isRootProject(project)) {
      project.getPlugins().apply(BuildOptionsPlugin.class);
      var ext =
          project
              .getExtensions()
              .create(RootTestingProjectExtension.NAME, RootTestingProjectExtension.class);

      installVerboseCheckHook(project);
      installRootSeed(project, ext);
      installGlobalTestsSummary(project, ext);
    }

    project.getExtensions().create(TestingProjectExtension.NAME, TestingProjectExtension.class);
    project
        .getPlugins()
        .withType(
            JavaBasePlugin.class,
            plugin -> {
              applyTestingEnv(project);
            });
  }

  private void installGlobalTestsSummary(Project project, RootTestingProjectExtension ext) {
    TestSummary testSummary = new TestSummary();
    ext.getTestSummary().set(testSummary);
    var configurationCache = getBuildFeatures().getConfigurationCache();
    if (configurationCache.getRequested().getOrElse(false)) {
      project.getLogger().warn("Global test summary does not work with the configuration cache.");
    } else {
      project
          .getTasks()
          .register(
              ALL_TESTS_SUMMARY_TASK_NAME,
              Task.class,
              t -> {
                t.doFirst(
                    task -> {
                      if (testSummary.testTasksExecuted > 0) {
                        StringBuilder msg = new StringBuilder();
                        msg.append(
                            pluralize("test task", testSummary.testTasksExecuted)
                                + " executed"
                                + ", "
                                + pluralize("test", testSummary.tests));
                        if (testSummary.failures > 0) {
                          msg.append(", " + pluralize("failure", testSummary.failures));
                        }
                        if (testSummary.ignored > 0) {
                          msg.append(", " + testSummary.ignored + " ignored");
                        }
                        project.getLogger().lifecycle(msg.toString());
                      }
                    });
              });
    }
  }

  private static String pluralize(String word, long count) {
    return "" + count + " " + (count == 1 ? word : word + "s");
  }

  private static void installRootSeed(Project project, RootTestingProjectExtension ext) {
    var rootSeedOption =
        project
            .getExtensions()
            .getByType(BuildOptionsExtension.class)
            .addOption(
                "tests.seed",
                "Root randomization seed for randomizedtesting.",
                project.provider(() -> String.format("%08X", new Random().nextLong())));

    Property<String> rootSeed = ext.getRootSeed();
    rootSeed.set(rootSeedOption.asStringProvider());
    rootSeed.finalizeValueOnRead();

    project
        .getTasks()
        .register(
            PRINT_RANDOMIZATION_SEED_INFO_TASK_NAME,
            Task.class,
            t -> {
              t.doFirst(
                  task -> {
                    task.getLogger()
                        .lifecycle("Root randomization seed (tests.seed): " + rootSeed.get());
                  });
            });
  }

  private static void installVerboseCheckHook(Project project) {
    if (project.getGradle().getStartParameter().getMaxWorkerCount() > 1) {
      project
          .getGradle()
          .getTaskGraph()
          .whenReady(
              graph -> {
                boolean verboseMode =
                    project.getAllprojects().stream()
                        .anyMatch(
                            p -> {
                              if (p.getPlugins().hasPlugin(BuildOptionsPlugin.class)) {
                                var buildOptions =
                                    p.getExtensions().getByType(BuildOptionsExtension.class);
                                if (buildOptions.hasOption("tests.verbose")) {
                                  return buildOptions
                                      .getOption("tests.verbose")
                                      .asBooleanProvider()
                                      .get();
                                }
                              }
                              return false;
                            });
                if (verboseMode) {
                  var testTasksCount =
                      project.getAllprojects().stream()
                          .mapToLong(
                              p -> {
                                var ext =
                                    p.getExtensions().findByType(TestingProjectExtension.class);
                                if (ext == null) return 0;
                                return ext.getTestTasks(p).size();
                              })
                          .sum();

                  if (testTasksCount > 1) {
                    throw new GradleException(
                        "Run only one test task in verbose mode or pass --max-workers=1 option "
                            + "to gradle to prevent mangled console output.");
                  }
                }
              });
    }
  }

  private void applyTestingEnv(Project project) {
    project.getPlugins().apply(BuildOptionsPlugin.class);

    BuildOptionsExtension buildOptions =
        project.getExtensions().getByType(BuildOptionsExtension.class);
    var testTasks =
        project.getExtensions().getByType(TestingProjectExtension.class).getTestTasks(project);
    configureReproduceLineExtension(testTasks);
    configureHtmlReportsOption(buildOptions, testTasks);
    configureTestTaskOptions(project, buildOptions, testTasks);
    configureRandomizedTestingOptions(
        project, buildOptions, testTasks, ":" + PRINT_RANDOMIZATION_SEED_INFO_TASK_NAME);
    configureGlobalTestSummary(project, testTasks);
  }

  private void configureReproduceLineExtension(TaskCollection<Test> testTasks) {
    testTasks.configureEach(
        task -> {
          task.getExtensions()
              .create("reproduceLine", ReproduceLineExtension.class, task.getPath());
        });
  }

  private void configureGlobalTestSummary(Project project, TaskCollection<Test> testTasks) {
    var configurationCache = getBuildFeatures().getConfigurationCache();
    if (configurationCache.getRequested().getOrElse(false)) {
      // Just ignore, won't work.
      return;
    }

    var testSummary =
        project
            .getRootProject()
            .getExtensions()
            .findByType(RootTestingProjectExtension.class)
            .getTestSummary()
            .get();

    testTasks.configureEach(
        task -> {
          task.finalizedBy(":" + ALL_TESTS_SUMMARY_TASK_NAME);

          testSummary.incrementTasks();
          task.addTestListener(
              new TestListener() {
                @Override
                public void beforeSuite(TestDescriptor suite) {}

                @Override
                public void afterSuite(TestDescriptor suite, TestResult result) {
                  testSummary.suiteResult(suite, result);
                }

                @Override
                public void beforeTest(TestDescriptor testDescriptor) {}

                @Override
                public void afterTest(TestDescriptor testDescriptor, TestResult result) {
                  testSummary.testResult(testDescriptor, result);
                }
              });
        });
  }

  /** Configure test options specific to the randomizedtesting package. */
  private void configureRandomizedTestingOptions(
      Project project,
      BuildOptionsExtension buildOptions,
      TaskCollection<Test> testTasks,
      String printSeedTaskName) {

    var stackFilteringOption =
        buildOptions.addOption(
            "tests.stackfiltering",
            "Removes internal JDK and randomizedtesting stack frames from stack dumps.",
            "true");
    var itersOption =
        buildOptions.addOption(
            "tests.iters", "Repeats randomized tests the provided number of times.");
    var filterOption =
        buildOptions.addOption(
            "tests.filter", "Apply test group filtering using Boolean expressions.");
    var timeoutOption = buildOptions.addOption("tests.timeout", "Test timeout (in millis).");
    var timeoutSuiteOption =
        buildOptions.addOption("tests.timeoutSuite", "Test suite timeout (in millis).");
    var assertsOption =
        buildOptions.addOption(
            "tests.asserts",
            "The desired assertions status for RequireAssertionsRule (true/false).");

    var ext = project.getRootProject().getExtensions().getByType(RootTestingProjectExtension.class);
    var rootSeed = ext.getRootSeed();
    testTasks.configureEach(
        task -> {
          task.dependsOn(printSeedTaskName);
          task.systemProperty("tests.seed", rootSeed.get());

          ReproduceLineExtension reproLineExtension =
              task.getExtensions().getByType(ReproduceLineExtension.class);
          reproLineExtension.addGradleProperty("tests.seed", rootSeed.get());

          for (var opt :
              List.of(
                  assertsOption,
                  itersOption,
                  filterOption,
                  timeoutOption,
                  timeoutSuiteOption,
                  stackFilteringOption)) {
            if (opt.getValue().isPresent()) {
              String optName = opt.getName();
              String value = opt.asStringProvider().get();
              task.systemProperty(optName, value);
              reproLineExtension.addBuildOption(opt);
            }
          }
        });
  }

  /** Configure tunable defaults for all Test tasks. */
  private void configureTestTaskOptions(
      Project project, BuildOptionsExtension buildOptions, TaskCollection<Test> testTasks) {

    var rerunOption = buildOptions.addOption("tests.rerun", "Force Test task re-run.");
    var jvmArgsOption =
        buildOptions.addOption(
            "tests.jvmargs",
            "Additional arguments to pass directly to the forked test runner JVMs.");
    var minHeapOption = buildOptions.addOption("tests.minheap", "Minimum heap size for test JVMs.");
    var maxHeapOption = buildOptions.addOption("tests.maxheap", "Maximum heap size for test JVMs.");
    var jvmsOption =
        buildOptions.addOption(
            "tests.jvms",
            "The number of forked test JVMs.",
            project.getProviders().provider(() -> Integer.toString(defaultTestJvms())));

    var verboseOption =
        buildOptions.addOption(
            "tests.verbose", "Echo all stdout/stderr from tests to gradle console.", "false");

    var cwdDirOption =
        buildOptions.addOption(
            "tests.cwd.dir",
            "Current working directory for test JVMs (build-dir relative).",
            project.provider(() -> buildDirRelative(project, "test-cwd").toString()));

    var tmpDirOption =
        buildOptions.addOption(
            "tests.tmp.dir",
            "Temporary directory for test JVMs (build-dir relative).",
            project.provider(() -> buildDirRelative(project, "test-tmp").toString()));

    testTasks.configureEach(
        task -> {
          var projectDir = project.getLayout().getProjectDirectory();

          var reproLineExtension = task.getExtensions().getByType(ReproduceLineExtension.class);

          // do not fail immediately.
          task.getFilter().setFailOnNoMatchingTests(false);

          Provider<Directory> cwdDir = projectDir.dir(cwdDirOption.asStringProvider());
          task.setWorkingDir(cwdDir);
          task.doFirst(
              t -> {
                try {
                  Files.createDirectories(cwdDir.get().getAsFile().toPath());
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });

          Provider<Directory> tmpDir = projectDir.dir(tmpDirOption.asStringProvider());
          task.systemProperty("java.io.tmpdir", tmpDir.get().getAsFile().getAbsolutePath());
          task.doFirst(
              t -> {
                try {
                  Files.createDirectories(tmpDir.get().getAsFile().toPath());
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });

          if (rerunOption.getValue().isPresent() && rerunOption.asBooleanProvider().get()) {
            task.getOutputs()
                .upToDateWhen(
                    t -> {
                      return false;
                    });
          }

          if (jvmArgsOption.getValue().isPresent()) {
            String jvmArgs = jvmArgsOption.getValue().get().value();
            reproLineExtension.addGradleProperty("tests.jvmargs", jvmArgs);

            task.getJvmArgumentProviders()
                .add(
                    new CommandLineArgumentProvider() {
                      @Override
                      public Iterable<String> asArguments() {
                        return Arrays.asList(
                            Commandline.translateCommandline(
                                jvmArgsOption.asStringProvider().get()));
                      }
                    });
          }

          if (minHeapOption.getValue().isPresent()) {
            task.setMinHeapSize(minHeapOption.asStringProvider().get());
          }
          if (maxHeapOption.getValue().isPresent()) {
            task.setMaxHeapSize(maxHeapOption.asStringProvider().get());
          }

          boolean verboseMode = verboseOption.asBooleanProvider().get();

          // set the maximum number of parallel forks.
          if (verboseMode || jvmsOption.getValue().isPresent()) {
            int forks = jvmsOption.asIntProvider().getOrElse(1);
            if (verboseMode && forks > 1) {
              task.getLogger().info("${task.path}.maxParallelForks forced to 1 in verbose mode.");
              forks = 1;
            }
            task.setMaxParallelForks(forks);
          }

          // install stdout/stderr handlers.
          installOutputHandlers(task, getFilesystemOps(), verboseMode);
        });
  }

  private static @NotNull Path buildDirRelative(Project project, String dir) {
    return project
        .getProjectDir()
        .toPath()
        .relativize(project.getLayout().getBuildDirectory().dir(dir).get().getAsFile().toPath());
  }

  /** Set up error logging and a custom error stream redirector. */
  private void installOutputHandlers(
      Test task, FileSystemOperations filesystemOps, boolean verboseMode) {
    var stackFiltering =
        task.getProject()
            .getExtensions()
            .getByType(BuildOptionsExtension.class)
            .getOption("tests.stackfiltering")
            .asBooleanProvider()
            .getOrElse(true);

    TestLoggingContainer container = task.getTestLogging();
    container.events();
    container.setExceptionFormat(TestExceptionFormat.SHORT);
    container.setShowExceptions(true);
    container.setShowCauses(false);
    container.setShowStackTraces(false);
    container.setShowStandardStreams(false);
    if (!stackFiltering) {
      container.getStackTraceFilters().clear();
    }

    Path spillDir = task.getTemporaryDir().toPath();
    Path testOutputsDir =
        task.getProject()
            .getLayout()
            .getBuildDirectory()
            .get()
            .getAsFile()
            .toPath()
            .resolve(TEST_OUTPUTS_DIR)
            .resolve(task.getName());

    task.doFirst(
        (t) -> {
          filesystemOps.delete(
              spec -> {
                spec.delete(testOutputsDir.toFile());
              });
        });

    DefaultTestLogging logging = new DefaultTestLogging();
    logging.events(TestLogEvent.FAILED);
    logging.setExceptionFormat(TestExceptionFormat.FULL);
    logging.setShowExceptions(true);
    logging.setShowCauses(true);
    logging.setShowStackTraces(true);
    if (!stackFiltering) {
      logging.getStackTraceFilters().clear();
    }

    StyledTextOutput styledOut;
    if (getBuildFeatures().getConfigurationCache().getRequested().getOrElse(false)) {
      styledOut = null;
    } else {
      styledOut = getStyledOutputFactory().create(this.getClass());
    }

    var listener =
        new ErrorReportingTestListener(
            task.getLogger(),
            styledOut,
            task.getExtensions().findByType(ReproduceLineExtension.class),
            logging,
            spillDir,
            testOutputsDir,
            verboseMode);
    task.addTestOutputListener(listener);
    task.addTestListener(listener);
  }

  /**
   * The default number of JVMs is dynamic and depends on the number of available CPUs. Capped at 8.
   */
  private static int defaultTestJvms() {
    return ((int) Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() / 2.0, 8.0)));
  }

  /** Disable HTML report generation. The reports are big and slow to generate. */
  private static void configureHtmlReportsOption(
      BuildOptionsExtension buildOptions, TaskCollection<Test> testTasks) {
    var htmlReportsOption =
        buildOptions
            .addOption("tests.htmlReports", "Enable HTML report generation.", "false")
            .asBooleanProvider();
    testTasks.configureEach(
        task -> {
          task.reports(
              reports -> {
                reports.getHtml().getRequired().set(htmlReportsOption);
              });
        });
  }
}
