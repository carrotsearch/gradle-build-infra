package buildinfra;

import buildinfra.buildoptions.BuildOptionsPlugin;
import buildinfra.conventions.ApplyRegisterCommonTasksPlugin;
import buildinfra.environment.GradleConsistentWithWrapperPlugin;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;

public class BuildInfraPlugin extends AbstractPlugin {
  @Inject
  public BuildInfraPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    super.pluginAppliedToRootProject(project);

    // apply other root-level, environment validation plugins.
    project.getPlugins().apply(GradleConsistentWithWrapperPlugin.class);

    // register extensions.
    var ext = project.getExtensions().create(BuildInfraExtension.NAME, BuildInfraExtension.class);
    List<String> taskNames = project.getGradle().getStartParameter().getTaskNames();
    ext.getIntelliJIdea()
        .value(
            project
                .getProviders()
                .provider(
                    () -> {
                      return System.getProperty("idea.active") != null
                          || taskNames.contains("idea")
                          || taskNames.contains("cleanIdea");
                    }))
        .finalizeValueOnRead();

    // apply all common subproject plugins.
    project.allprojects(
        subproject -> {
          subproject.getPlugins().apply(BuildOptionsPlugin.class);
          subproject.getPlugins().apply(ApplyRegisterCommonTasksPlugin.class);
        });
  }
}
