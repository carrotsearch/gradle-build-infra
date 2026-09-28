package com.carrotsearch.gradle.buildinfra.plugins.misc;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsExtension;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin;
import com.carrotsearch.gradle.buildinfra.utils.TabularOutput;
import com.carrotsearch.gradle.buildinfra.utils.UnitFormatter;
import com.carrotsearch.gradle.buildinfra.utils.Units;
import java.io.StringWriter;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskSuccessResult;

/**
 * Prints aggregate wall-clock times for all executed gradle tasks (collected across all projects).
 */
public class MeasureTaskTimesPlugin extends AbstractPlugin {
  public static final String OPT_TASK_TIMES = "task.times";
  public static final String OPT_TASK_TIMES_AGGREGATE = "task.times.aggregate";
  public static final String OPT_TASK_TIMES_LIMIT = "task.times.limit";
  public static final String OPT_TASK_TIMES_MIN_TIME = "task.times.mintime.millis";
  public static final String OPT_TASK_TIMES_TIME_FORMAT = "task.times.time.format";

  /** Duration formats for the report. */
  public enum TimeFormat {
    COMPACT,
    NORMAL,
    FULL;

    UnitFormatter formatter() {
      return switch (this) {
        case COMPACT -> Units.DURATION_COMPACT;
        case NORMAL -> Units.DURATION;
        case FULL -> Units.DURATION_VERBOSE;
      };
    }
  }

  private final BuildEventsListenerRegistry listenerRegistry;

  @Inject
  public MeasureTaskTimesPlugin(Problems problems, BuildEventsListenerRegistry listenerRegistry) {
    super(problems);
    this.listenerRegistry = listenerRegistry;
  }

  @Override
  public void apply(Project project) {
    super.pluginAppliedToRootProject(project);

    project.getPlugins().apply(BuildOptionsPlugin.class);
    var buildOptions = project.getExtensions().getByType(BuildOptionsExtension.class);

    var taskTimesOption =
        buildOptions.addBooleanOption(
            OPT_TASK_TIMES,
            "Measures wall-time task execution and provides a summary of the longest tasks at "
                + "the end of a successful build. Enabled by default on CI builds (when the CI "
                + "environment variable is set).",
            project.getProviders().environmentVariable("CI").map(v -> true).orElse(false));

    var aggregateOption =
        buildOptions.addBooleanOption(
            OPT_TASK_TIMES_AGGREGATE,
            "Aggregate task times by unique name (across all projects). If false, unique task paths are printed.",
            true);

    var taskCountLimitOption =
        buildOptions.addIntOption(OPT_TASK_TIMES_LIMIT, "Limit the list to the top-N tasks.", 20);

    var minTimeOption =
        buildOptions.addIntOption(
            OPT_TASK_TIMES_MIN_TIME,
            "Omit tasks that took less than this number of milliseconds (0 to report all).",
            100);

    var timeFormatOption =
        buildOptions.addOption(
            OPT_TASK_TIMES_TIME_FORMAT,
            "Format of reported durations (compact, normal, full).",
            "compact");

    if (!taskTimesOption.get()) {
      return;
    }

    TimeFormat timeFormat;
    try {
      timeFormat = TimeFormat.valueOf(timeFormatOption.get().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw reportError(
          "task-times-invalid-time-format",
          "Invalid value of the "
              + OPT_TASK_TIMES_TIME_FORMAT
              + " option: "
              + timeFormatOption.get()
              + " (expected one of: compact, normal, full).");
    }

    Provider<TaskTimesService> taskTimesService =
        project
            .getGradle()
            .getSharedServices()
            .registerIfAbsent(
                "taskTimesService",
                TaskTimesService.class,
                service -> {
                  service.parameters(
                      params -> {
                        params.getAggregateByName().set(aggregateOption.get());
                        params.getLimit().set(taskCountLimitOption.get());
                        params.getMinTimeMillis().set(minTimeOption.get());
                        params.getTimeFormat().set(timeFormat);
                      });
                });

    listenerRegistry.onTaskCompletion(taskTimesService);
  }

  /**
   * This service collects per-task durations and prints a summary when closed (at the end of the
   * build).
   */
  public abstract static class TaskTimesService
      implements BuildService<TaskTimesServiceParams>, OperationCompletionListener, AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(TaskTimesService.class);

    /** Execution time of all successful tasks. Keys are task paths, values are in millis. */
    private final ConcurrentHashMap<String, Long> taskTimes = new ConcurrentHashMap<>();

    private volatile boolean hadFailedTask;

    @Override
    public void onFinish(FinishEvent event) {
      if (event instanceof TaskFinishEvent tfe) {
        if (tfe.getResult() instanceof TaskSuccessResult successResult) {
          long durationMillis =
              Math.max(0, successResult.getEndTime() - successResult.getStartTime());
          String taskPath = tfe.getDescriptor().getTaskPath();
          taskTimes.merge(taskPath, durationMillis, Long::sum);
        }

        if (tfe.getResult() instanceof TaskFailureResult) {
          hadFailedTask = true;
        }
      }
    }

    private static String simpleTaskName(String path) {
      int idx = path.lastIndexOf(':');
      return (idx >= 0 && idx < path.length() - 1) ? path.substring(idx + 1) : path;
    }

    @Override
    public void close() {
      if (taskTimes.isEmpty() || hadFailedTask) return;

      var params = getParameters();
      int limit = params.getLimit().get();
      long minTimeMillis = params.getMinTimeMillis().get();
      UnitFormatter durationFormat = params.getTimeFormat().get().formatter();

      Map<String, Long> localTaskTimes = taskTimes;

      if (params.getAggregateByName().get()) {
        localTaskTimes =
            localTaskTimes.entrySet().stream()
                .collect(Collectors.groupingBy(e -> simpleTaskName(e.getKey())))
                .entrySet()
                .stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream().mapToLong(Map.Entry::getValue).sum()));
      }

      var tabular =
          TabularOutput.to(new StringWriter())
              .columnSeparator("  ")
              .noAutoFlush()
              .outputHeaders(false)
              .addColumn("time", TabularOutput.ColumnSpec::alignRight)
              .addColumn("task", TabularOutput.ColumnSpec::alignLeft)
              .build();

      localTaskTimes.entrySet().stream()
          .filter(e -> e.getValue() >= minTimeMillis)
          .sorted(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed())
          .limit(limit)
          .forEachOrdered(
              e ->
                  tabular.append("  " + durationFormat.format(e.getValue()), e.getKey()).nextRow());

      LOGGER.lifecycle(
          "\nSummary of task execution times (top-{}{}, {}):\n{}",
          limit,
          minTimeMillis > 0 ? ", >" + durationFormat.format(minTimeMillis) : "",
          params.getAggregateByName().get()
              ? "aggregated by unique name, possibly running in parallel"
              : "unique task paths",
          tabular
              .flush()
              .getWriter()
              .toString()
              .lines()
              .map(String::stripTrailing)
              .collect(Collectors.joining("\n")));
    }
  }

  public interface TaskTimesServiceParams extends BuildServiceParameters {
    Property<Boolean> getAggregateByName();

    Property<Integer> getLimit();

    Property<Integer> getMinTimeMillis();

    Property<TimeFormat> getTimeFormat();
  }
}
