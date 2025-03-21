package buildinfra;

import buildinfra.buildoptions.BuildOptionsPlugin;
import buildinfra.conventions.*;
import buildinfra.dependencychecks.DependencyChecksPlugin;
import buildinfra.environment.GitInfoPlugin;
import buildinfra.environment.GradleConsistentWithWrapperPlugin;
import java.util.List;
import javax.inject.Inject;

import buildinfra.testing.TestingEnvPlugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.problems.Problems;

public class BuildInfraPlugin extends AbstractPlugin {
  @Inject
  public BuildInfraPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project rootProject) {
    super.pluginAppliedToRootProject(rootProject);

    // apply other root-level, environment validation plugins.
    rootProject.getPlugins().apply(GradleConsistentWithWrapperPlugin.class);

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

    // apply sub-plugins.
    rootProject.getPlugins().apply(GitInfoPlugin.class);

    // apply all common subproject plugins.
    rootProject.allprojects(
        subproject -> {
          var pluginContainer = subproject.getPlugins();
          pluginContainer.apply(BuildOptionsPlugin.class);
          pluginContainer.apply(ApplyRegisterCommonTasksPlugin.class);
          pluginContainer.apply(ApplyReproducibleBuildsPlugin.class);
          pluginContainer.apply(ApplyForbiddenApisPlugin.class);
          pluginContainer.apply(ApplySpotlessFormattingPlugin.class);
          pluginContainer.apply(TestingEnvPlugin.class);
          pluginContainer.apply(DependencyChecksPlugin.class);
          pluginContainer.apply(ApplyVersionsTomlCleanupsPlugin.class);
        });
  }
}
