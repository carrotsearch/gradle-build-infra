package com.carrotsearch.gradle.buildinfra;

import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsExtension;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionsPlugin;
import com.carrotsearch.gradle.buildinfra.dependencychecks.DependencyChecksPlugin;
import com.carrotsearch.gradle.buildinfra.forbiddenapis.ApplyForbiddenApisPlugin;
import com.carrotsearch.gradle.buildinfra.gitinfo.GitInfoPlugin;
import com.carrotsearch.gradle.buildinfra.gradlewrapper.GradleConsistentWithWrapperPlugin;
import com.carrotsearch.gradle.buildinfra.javadefaults.ApplySaneJavaDefaultsPlugin;
import com.carrotsearch.gradle.buildinfra.misc.ApplyRegisterCommonTasksPlugin;
import com.carrotsearch.gradle.buildinfra.misc.ApplyVersionsTomlCleanupsPlugin;
import com.carrotsearch.gradle.buildinfra.optiongroups.BuildOptionGroupsPlugin;
import com.carrotsearch.gradle.buildinfra.reproduciblebuilds.ApplyReproducibleBuildsPlugin;
import com.carrotsearch.gradle.buildinfra.spotless.ApplySpotlessFormattingPlugin;
import com.carrotsearch.gradle.buildinfra.tasktimes.MeasureTaskTimesPlugin;
import com.carrotsearch.gradle.buildinfra.testing.ShowFailedTestsAtEndPlugin;
import com.carrotsearch.gradle.buildinfra.testing.ShowSlowestTestsAtEndPlugin;
import com.carrotsearch.gradle.buildinfra.testing.ShowTestsSummaryAtEndPlugin;
import com.carrotsearch.gradle.buildinfra.testing.TestingEnvPlugin;
import java.util.EnumSet;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Provider;

/**
 * The umbrella plugin: applies all sub-plugins, each of which can be switched off with a build
 * option named {@code buildinfra.<plugin>} (see {@link SubPlugin}).
 */
public class BuildInfraPlugin extends AbstractPlugin {
  /** Where a sub-plugin is applied. */
  enum Scope {
    ROOT,
    ALL_PROJECTS
  }

  /** Sub-plugins that can be switched on or off with a {@code buildinfra.<id>} build option. */
  public enum SubPlugin {
    GIT_INFO(
        "gitInfo",
        "Git repository information (gitinfo extension).",
        Scope.ROOT,
        GitInfoPlugin.class),
    REPRODUCIBLE_BUILDS(
        "reproducibleBuilds",
        "Reproducible archive tasks.",
        Scope.ALL_PROJECTS,
        ApplyReproducibleBuildsPlugin.class),
    FORBIDDEN_APIS(
        "forbiddenApis",
        "forbidden-apis checks.",
        Scope.ALL_PROJECTS,
        ApplyForbiddenApisPlugin.class),
    SPOTLESS(
        "spotless",
        "Spotless formatting of Java sources and gradle scripts.",
        Scope.ALL_PROJECTS,
        ApplySpotlessFormattingPlugin.class),
    JAVA_DEFAULTS(
        "javaDefaults",
        "Java compilation defaults (minJava, UTF-8).",
        Scope.ALL_PROJECTS,
        ApplySaneJavaDefaultsPlugin.class),
    TESTING(
        "testing",
        "Test task conventions and randomizedtesting options.",
        Scope.ALL_PROJECTS,
        TestingEnvPlugin.class),
    TESTS_SUMMARY(
        "testsSummary",
        "Summary of all executed tests at the end of the build.",
        Scope.ALL_PROJECTS,
        ShowTestsSummaryAtEndPlugin.class),
    SLOWEST_TESTS(
        "slowestTests",
        "The slowest tests and suites at the end of the build.",
        Scope.ALL_PROJECTS,
        ShowSlowestTestsAtEndPlugin.class),
    FAILED_TESTS(
        "failedTests",
        "Failed tests, with reproduce lines, at the end of the build (requires testing).",
        Scope.ALL_PROJECTS,
        ShowFailedTestsAtEndPlugin.class),
    DEPENDENCY_CHECKS(
        "dependencyChecks",
        "Dependency version consistency checks (lock file).",
        Scope.ALL_PROJECTS,
        DependencyChecksPlugin.class),
    VERSION_CATALOG_UPDATES(
        "versionCatalogUpdates",
        "Version catalog update tasks (updateVersions).",
        Scope.ALL_PROJECTS,
        ApplyVersionsTomlCleanupsPlugin.class),
    TASK_TIMES(
        "taskTimes",
        "Summary of task execution times at the end of the build (default: on for CI builds).",
        Scope.ROOT,
        MeasureTaskTimesPlugin.class);

    public final String id;
    public final String description;
    final Scope scope;
    final Class<? extends Plugin<Project>> pluginClass;

    SubPlugin(
        String id, String description, Scope scope, Class<? extends Plugin<Project>> pluginClass) {
      this.id = id;
      this.description = description;
      this.scope = scope;
      this.pluginClass = pluginClass;
    }

    /** The build option switching this plugin on or off. */
    public String optionName() {
      return "buildinfra." + id;
    }

    Provider<Boolean> defaultValue(Project rootProject) {
      if (this == TASK_TIMES) {
        return rootProject.getProviders().environmentVariable("CI").map(v -> true).orElse(false);
      }
      return rootProject.provider(() -> true);
    }
  }

  @Inject
  public BuildInfraPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project rootProject) {
    super.pluginAppliedToRootProject(rootProject);

    // Build options come first: sub-plugins are switched on/off with them.
    rootProject.getPlugins().apply(BuildOptionsPlugin.class);
    var buildOptions = rootProject.getExtensions().getByType(BuildOptionsExtension.class);
    failIfDeprecatedOptionUsed(buildOptions, "task.times");
    failIfDeprecatedOptionUsed(buildOptions, "tests.slowestTests");

    EnumSet<SubPlugin> enabled = EnumSet.noneOf(SubPlugin.class);
    for (SubPlugin plugin : SubPlugin.values()) {
      var option =
          buildOptions.addBooleanOption(
              plugin.optionName(),
              "Apply the sub-plugin: " + plugin.description,
              plugin.defaultValue(rootProject));
      if (option.get()) {
        enabled.add(plugin);
      }
    }

    // apply other root-level, environment validation plugins.
    rootProject.getPlugins().apply(GradleConsistentWithWrapperPlugin.class);

    rootProject.getTasks().register("noop", t -> {});

    // register extensions.
    var ext =
        rootProject.getExtensions().create(BuildInfraExtension.NAME, BuildInfraExtension.class);
    List<String> taskNames = rootProject.getGradle().getStartParameter().getTaskNames();
    ext.getIntelliJIdea()
        .value(
            rootProject
                .getProviders()
                .provider(
                    () -> {
                      return System.getProperty("idea.active") != null
                          || taskNames.contains("idea")
                          || taskNames.contains("cleanIdea");
                    }))
        .finalizeValueOnRead();

    // root-level sub-plugins.
    for (SubPlugin plugin : enabled) {
      if (plugin.scope == Scope.ROOT) {
        rootProject.getPlugins().apply(plugin.pluginClass);
      }
    }

    // sub-plugins applied to all projects.
    rootProject.allprojects(
        subproject -> {
          var pluginContainer = subproject.getPlugins();
          pluginContainer.apply(BuildOptionsPlugin.class);
          pluginContainer.apply(BuildOptionGroupsPlugin.class);
          pluginContainer.apply(ApplyRegisterCommonTasksPlugin.class);
          for (SubPlugin plugin : enabled) {
            if (plugin.scope == Scope.ALL_PROJECTS) {
              pluginContainer.apply(plugin.pluginClass);
            }
          }
        });
  }
}
