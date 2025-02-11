package buildinfra.conventions;

import buildinfra.AbstractPlugin;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;

public class BuildConventionsPlugin extends AbstractPlugin {
  public static String OPTIONS_EXTENSION_NAME = "buildConventions";

  @Inject
  public BuildConventionsPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    BuildConventionsExtension options =
        project.getObjects().newInstance(BuildConventionsExtension.class);
    project.getExtensions().add(OPTIONS_EXTENSION_NAME, options);
  }
}
