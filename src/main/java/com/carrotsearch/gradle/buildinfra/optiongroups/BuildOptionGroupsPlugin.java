package com.carrotsearch.gradle.buildinfra.optiongroups;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import com.carrotsearch.gradle.buildinfra.BuildInfraPlugin;
import com.carrotsearch.gradle.buildinfra.DeprecatedBuildOptions;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsTask;
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
                        "Build-infra plugins (enable/disable)",
                        explicitList(
                            Stream.of(BuildInfraPlugin.SubPlugin.values())
                                .map(BuildInfraPlugin.SubPlugin::optionName)
                                .toArray(String[]::new)));

                    optionGroups.group("Build-infra plugin options", "buildinfra\\.[^.]+\\..+");

                    optionGroups.group(
                        "Test reports and output",
                        explicitList(
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

                    optionGroups.group(
                        "Deprecated options (renamed)",
                        explicitList(
                            DeprecatedBuildOptions.RENAMES.keySet().toArray(String[]::new)));

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
