package buildinfra.conventions;

import buildinfra.AbstractPlugin;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;

public class ApplyRegisterCommonTasksPlugin extends AbstractPlugin {
  @Inject
  public ApplyRegisterCommonTasksPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    project.getTasks().register("tidy").configure(task -> {});
  }
}
