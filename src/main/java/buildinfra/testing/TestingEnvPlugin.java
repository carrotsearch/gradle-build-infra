package buildinfra.testing;

import buildinfra.AbstractPlugin;
import buildinfra.buildoptions.BuildOptionsExtension;
import buildinfra.buildoptions.BuildOptionsPlugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import org.apache.tools.ant.types.Commandline;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.internal.tasks.testing.logging.DefaultTestLogging;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLogEvent;
import org.gradle.api.tasks.testing.logging.TestLoggingContainer;
import org.gradle.process.CommandLineArgumentProvider;
import org.jetbrains.annotations.NotNull;

public class TestingEnvPlugin extends AbstractPlugin {
  static final String TEST_OUTPUTS_DIR = "test-outputs";
  private final FileSystemOperations filesystemOps;

  private AtomicBoolean warnOnce = new AtomicBoolean(true);

  @Inject
  public TestingEnvPlugin(Problems problems, FileSystemOperations filesystemOps) {
    super(problems);
    this.filesystemOps = filesystemOps;
  }

  @Override
  public void apply(Project project) {
    Spec<? super Test> testTaskFilter = t -> true;

    if (project.getRootProject() == project
        && project.getGradle().getStartParameter().getMaxWorkerCount() > 1) {
      project
          .getGradle()
          .getTaskGraph()
          .whenReady(
              graph -> {
                var testTasksCount =
                    graph.getAllTasks().stream()
                        .filter(t -> t instanceof Test)
                        .filter(t -> testTaskFilter.isSatisfiedBy((Test) t))
                        .count();
                if (testTasksCount > 1) {
                  if (warnOnce.getAndSet(false)) {
                    throw new GradleException(
                        "Run only one test task in verbose mode or pass --max-workers=1 option "
                            + "to gradle to prevent mangled console output.");
                  }
                }
              });
    }

    project
        .getPlugins()
        .withType(
            JavaBasePlugin.class,
            plugin -> {
              applyTestingEnv(project, testTaskFilter);
            });
  }

  private void applyTestingEnv(Project project, Spec<? super Test> testTaskFilter) {
    project.getPlugins().apply(BuildOptionsPlugin.class);

    BuildOptionsExtension buildOptions =
        project.getExtensions().getByType(BuildOptionsExtension.class);
    TaskCollection<Test> testTasks =
        project.getTasks().withType(Test.class).matching(testTaskFilter);

    configureHtmlReportsOption(buildOptions, testTasks);
    configureTestTaskOptions(project, buildOptions, testTasks, testTaskFilter);
  }

  /** Configure tunable defaults for all Test tasks. */
  private void configureTestTaskOptions(
      Project project,
      BuildOptionsExtension buildOptions,
      TaskCollection<Test> testTasks,
      Spec<? super Test> testTaskFilter) {

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
          if (verboseMode || jvmsOption.getValue().isPresent()) {
            int forks = jvmsOption.asIntProvider().getOrElse(1);
            if (verboseMode && forks > 1) {
              task.getLogger().info("${task.path}.maxParallelForks forced to 1 in verbose mode.");
              forks = 1;
            }
            task.setMaxParallelForks(forks);
          }

          installOutputHandlers(task, filesystemOps, verboseMode);
        });
  }

  private static @NotNull Path buildDirRelative(Project project, String dir) {
    return project
        .getProjectDir()
        .toPath()
        .relativize(project.getLayout().getBuildDirectory().dir(dir).get().getAsFile().toPath());
  }

  /** Set up error logging and a custom error stream redirector. */
  private static void installOutputHandlers(
      Test task, FileSystemOperations filesystemOps, boolean verboseMode) {
    TestLoggingContainer container = task.getTestLogging();
    container.events();
    container.setExceptionFormat(TestExceptionFormat.SHORT);
    container.setShowExceptions(true);
    container.setShowCauses(false);
    container.setShowStackTraces(false);
    container.getStackTraceFilters().clear();
    container.setShowStandardStreams(false);

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
    logging.getStackTraceFilters().clear();

    var listener =
        new ErrorReportingTestListener(
            task.getLogger(), logging, spillDir, testOutputsDir, verboseMode);
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
