package buildinfra.testing;

import buildinfra.AbstractPlugin;
import buildinfra.buildoptions.BuildOptionsExtension;
import buildinfra.buildoptions.BuildOptionsPlugin;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.problems.Problems;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.testing.Test;

public class TestingEnvPlugin extends AbstractPlugin {
  @Inject
  public TestingEnvPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    project
        .getPlugins()
        .withType(
            JavaBasePlugin.class,
            plugin -> {
              applyTestingEnv(project);
            });
  }

  private void applyTestingEnv(Project project) {
    project.getPlugins().apply(BuildOptionsPlugin.class);

    var buildOptions = project.getExtensions().getByType(BuildOptionsExtension.class);

    var htmlReportsOption =
        buildOptions
            .addOption("tests.htmlReports", "Enable HTML report generation.", "false")
            .getValue();

    TaskCollection<Test> testTasks = project.getTasks().withType(Test.class);

    // Disable HTML report generation. The reports are big and slow to generate.
    testTasks.configureEach(
        task -> {
          task.reports(
              reports -> {
                reports
                    .getHtml()
                    .getRequired()
                    .set(htmlReportsOption.map(v -> Boolean.parseBoolean(v.value())));
              });
        });
  }
}
