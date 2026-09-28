package com.carrotsearch.gradle.buildinfra.plugins.misc;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsTask;
import com.carrotsearch.gradle.buildinfra.conventions.ApplyForbiddenApisPlugin;
import com.carrotsearch.gradle.buildinfra.conventions.ApplySpotlessFormattingPlugin;
import com.carrotsearch.gradle.buildinfra.environment.GradleConsistentWithWrapperPlugin;
import com.carrotsearch.gradle.buildinfra.testing.ShowSlowestTestsAtEndPlugin;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;

/**
 * Groups the build options declared by buildinfra plugins into logical categories in the output of
 * the {@code buildOptions} task. Projects can add their own groups the same way:
 *
 * <pre>
 * tasks.withType(BuildOptionsTask).configureEach {
 *   optionGroups {
 *     group("My options", "myproject\\.(.*)")
 *   }
 * }
 * </pre>
 */
public class BuildOptionGroupsPlugin extends AbstractPlugin {
  @Inject
  public BuildOptionGroupsPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(BuildOptionsPlugin.class);

    project
        .getTasks()
        .withType(BuildOptionsTask.class)
        .configureEach(
            task -> {
              task.optionGroups(
                  optionGroups -> {
                    optionGroups.group(
                        "Build environment and conventions",
                        explicitList(
                            GradleConsistentWithWrapperPlugin.OPT_GRADLE_WRAPPER_CONSISTENCY,
                            ApplySpotlessFormattingPlugin.OPT_SPOTLESS_GRADLE_GROOVY_SCRIPTS,
                            ApplyForbiddenApisPlugin.OPT_FORBIDDEN_APIS_DIR));

                    optionGroups.group(
                        "Build control and information",
                        Pattern.quote(MeasureTaskTimesPlugin.OPT_TASK_TIMES) + "(\\..*)?");

                    optionGroups.group(
                        "Test reports and output",
                        explicitList(
                            ShowSlowestTestsAtEndPlugin.OPT_SLOWEST_TESTS,
                            ShowSlowestTestsAtEndPlugin.OPT_SLOWEST_TESTS_MIN_TIME,
                            ShowSlowestTestsAtEndPlugin.OPT_SLOWEST_SUITES,
                            ShowSlowestTestsAtEndPlugin.OPT_SLOWEST_SUITES_MIN_TIME,
                            "tests.echoOutputOnError",
                            "tests.verbose",
                            "tests.htmlReports",
                            "tests.stackfiltering"));

                    optionGroups.group(
                        "Test JVMs and execution",
                        explicitList(
                            "tests.jvms",
                            "tests.jvmargs",
                            "tests.minheap",
                            "tests.maxheap",
                            "tests.cwd.dir",
                            "tests.tmp.dir",
                            "tests.rerun"));

                    optionGroups.group(
                        "Test randomization (randomizedtesting)",
                        explicitList(
                            "tests.seed",
                            "tests.iters",
                            "tests.filter",
                            "tests.timeout",
                            "tests.timeoutSuite",
                            "tests.asserts"));

                    optionGroups.otherOptions("Other options");
                  });
            });
  }

  private static String explicitList(String... options) {
    return Stream.of(options)
        .map(opt -> "(" + Pattern.quote(opt) + ")")
        .collect(Collectors.joining("|"));
  }
}
