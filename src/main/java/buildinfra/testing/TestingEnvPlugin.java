package buildinfra.testing;

import buildinfra.AbstractPlugin;
import buildinfra.buildoptions.BuildOptionsExtension;
import buildinfra.buildoptions.BuildOptionsPlugin;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import org.apache.tools.ant.types.Commandline;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.flow.*;
import org.gradle.api.internal.tasks.testing.logging.DefaultTestLogging;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLogEvent;
import org.gradle.api.tasks.testing.logging.TestLoggingContainer;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.internal.configuration.problems.PropertyTrace;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.jetbrains.annotations.NotNull;

public abstract class TestingEnvPlugin extends AbstractPlugin {
  private static final String INTERNAL_VERBOSE_MODE_PROP = "internal:verboseMode";
  private static final String TEST_OUTPUTS_DIR = "test-outputs";
  private final FileSystemOperations filesystemOps;

  private AtomicBoolean warnOnce = new AtomicBoolean(true);

  @Inject
  protected abstract FlowScope getFlowScope();

  @Inject
  protected abstract FlowProviders getFlowProviders();

  @Inject
  public TestingEnvPlugin(
      Problems problems,
      FileSystemOperations filesystemOps,
      BuildEventsListenerRegistry eventsListenerRegistry) {
    super(problems);
    this.filesystemOps = filesystemOps;
  }

  public abstract static class TaskEventsParams implements BuildServiceParameters {
    abstract Property<Boolean> getHasTestParams();
  }

  public abstract static class TaskEventsService
      implements BuildService<TaskEventsParams>, Closeable, TestListener {

    private AtomicInteger executedTasks = new AtomicInteger();

    @Override
    public void close() throws IOException {}

    @Override
    public void beforeSuite(TestDescriptor suite) {}

    @Override
    public void afterSuite(TestDescriptor suite, TestResult result) {}

    @Override
    public void beforeTest(TestDescriptor testDescriptor) {}

    @Override
    public void afterTest(TestDescriptor testDescriptor, TestResult result) {}

    public void testTaskRun(Task taskRef) {
      executedTasks.incrementAndGet();
    }

    public void finish(BuildWorkResult buildWorkResult) {
      System.out.println(
          "Done: " + buildWorkResult.getFailure().isPresent() + " " + executedTasks.get());
    }
  }

  public abstract static class CheckAfterBuildParams implements FlowParameters {
    @ServiceReference()
    public abstract Property<TaskEventsService> getTaskEventsService();

    @Input
    public abstract Property<BuildWorkResult> getBuildWorkResult();
  }

  public static class CheckAfterBuild implements FlowAction<CheckAfterBuildParams> {
    @Override
    public void execute(CheckAfterBuildParams parameters) throws Exception {
      parameters.getTaskEventsService().get().finish(parameters.getBuildWorkResult().get());
    }
  }

  public abstract static class AbstractBuildEventsHook implements OperationCompletionListener {
    @Inject
    public abstract FlowScope getFlowScope();

    @Inject
    public abstract FlowProviders getFlowProviders();

    @Inject
    public abstract BuildEventsListenerRegistry getEventListenerRegistry();

    @Inject
    public abstract ProviderFactory getProviderFactory();

    @Inject
    public abstract TaskExecutionGraph getGraph();

    private final List<FinishEvent> buildEvents = new ArrayList<>();

    public abstract static class InternalFlowParams implements FlowParameters {
      @Input
      public abstract Property<BuildWorkResult> getBuildWorkResult();
    }

    public abstract static class InternalFlowAction implements FlowAction<InternalFlowParams> {
      @Inject
      public abstract Property<TaskExecutionGraph> getTaskExecutionGraph();

      @Override
      public void execute(InternalFlowParams parameters) throws Exception {
        System.out.println("Flow done: " + parameters.getBuildWorkResult().get());
        getTaskExecutionGraph()
            .get()
            .getAllTasks()
            .forEach(
                task -> {
                  System.out.println("T: " + task.getPath() + " => " + task.getState());
                });
      }
    }

    public void setup() {
      getEventListenerRegistry().onTaskCompletion(getProviderFactory().provider(() -> this));
      getGraph()
          .whenReady(
              (g) -> {
                System.out.println("Graph is ready.");
              });
      getFlowScope()
          .always(
              InternalFlowAction.class,
              spec -> {
                var params = spec.getParameters();
                params.getBuildWorkResult().set(getFlowProviders().getBuildWorkResult());
              });
    }

    public void onFinish(FinishEvent event) {
      synchronized (buildEvents) {
        buildEvents.add(event);
      }
      System.out.println("L: " + event);
    }
  }

  @Override
  public void apply(Project project) {
    Spec<? super Test> testTaskFilter = t -> true;

    System.out.println("Configuring: " + project.getPath());
    if (isRootProject(project)) {
      var foo = project.getObjects().newInstance(AbstractBuildEventsHook.class);
      foo.setup();
    }

    getFlowScope()
        .always(
            CheckAfterBuild.class,
            spec -> {
              spec.getParameters()
                  .getBuildWorkResult()
                  .set(getFlowProviders().getBuildWorkResult());
            });

    // set up the global 'fail on no tests' hook.
    if (true) {
      boolean hasTestFiltersArgs =
          project.getGradle().getStartParameter().getTaskNames().stream()
              .anyMatch(arg -> arg.equals("--tests"));

      Provider<TaskEventsService> taskEvents =
          project
              .getGradle()
              .getSharedServices()
              .registerIfAbsent(
                  "test-task-events",
                  TaskEventsService.class,
                  spec -> {
                    spec.getParameters().getHasTestParams().set(hasTestFiltersArgs);
                  });

      project
          .getTasks()
          .configureEach(
              t -> {
                if (t instanceof Test te && testTaskFilter.isSatisfiedBy(te)) {
                  te.addTestListener(taskEvents.get());
                  te.usesService(taskEvents);

                  te.doFirst(
                      (taskRef) -> {
                        taskEvents.get().testTaskRun(taskRef);
                      });
                }
              });
    }

    // warn if multiple workers are running in verbose mode.
    if (isRootProject(project) && project.getGradle().getStartParameter().getMaxWorkerCount() > 1) {
      project
          .getGradle()
          .getTaskGraph()
          .whenReady(
              graph -> {
                var testTasksCount =
                    graph.getAllTasks().stream()
                        .filter(t -> t instanceof Test)
                        .filter(t -> testTaskFilter.isSatisfiedBy((Test) t))
                        .filter(
                            t ->
                                t.getExtensions()
                                    .getExtraProperties()
                                    .has(INTERNAL_VERBOSE_MODE_PROP))
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

          // mark the task as running in verbose mode.
          if (verboseMode) {
            var ext = task.getExtensions().getExtraProperties();
            if (ext.has(INTERNAL_VERBOSE_MODE_PROP)) {
              throw new RuntimeException("wtf?");
            }
            task.getExtensions().getExtraProperties().set(INTERNAL_VERBOSE_MODE_PROP, "true");
          }

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
