package buildinfra.environment;

import buildinfra.AbstractPlugin;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;

public class GitInfoPlugin extends AbstractPlugin {
  @Inject
  public GitInfoPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    super.pluginAppliedToRootProject(project);

    var gitInfoProvider =
        project
            .getProviders()
            .of(
                GitInfoValueSource.class,
                spec -> {
                  spec.getParameters().getRootProjectDir().set(project.getProjectDir());
                });

    var gitInfoExtension =
        project.getExtensions().create(GitInfoExtension.NAME, GitInfoExtension.class);

    gitInfoExtension.getGitInfo().value(gitInfoProvider).finalizeValueOnRead();
  }
}
