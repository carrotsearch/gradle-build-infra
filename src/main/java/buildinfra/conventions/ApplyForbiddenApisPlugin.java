package buildinfra.conventions;

import buildinfra.AbstractPlugin;
import buildinfra.buildoptions.BuildOptionsExtension;
import buildinfra.buildoptions.BuildOptionsPlugin;
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis;
import de.thetaphi.forbiddenapis.gradle.ForbiddenApisPlugin;
import java.nio.file.Files;
import java.util.*;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.problems.Problems;
import org.gradle.api.tasks.TaskCollection;

public class ApplyForbiddenApisPlugin extends AbstractPlugin {

  @Inject
  public ApplyForbiddenApisPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(BuildOptionsPlugin.class);
    var buildOptions = project.getExtensions().getByType(BuildOptionsExtension.class);

    var forbiddenApisDir =
        project
            .getRootProject()
            .getLayout()
            .getProjectDirectory()
            .file("gradle/forbidden-apis")
            .getAsFile()
            .toPath();

    Directory projectDirectory = project.getLayout().getProjectDirectory();
    var forbiddenApisDirOption =
        buildOptions.addOption(
            "forbiddenApisDir",
            "Directory with per-dependency forbidden-apis rules.",
            projectDirectory.getAsFile().toPath().relativize(forbiddenApisDir).toString());

    RegularFileProperty forbiddenApisDirProperty =
        project
            .getObjects()
            .fileProperty()
            .convention(projectDirectory.file(forbiddenApisDirOption.asStringProvider()));

    project
        .getPlugins()
        .withType(
            JavaBasePlugin.class,
            plugin -> {
              applyForbiddenApis(project, forbiddenApisDirProperty);
            });
  }

  private void applyForbiddenApis(Project project, RegularFileProperty forbiddenApisDir) {
    project.getPlugins().apply(ForbiddenApisPlugin.class);

    var sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();

    TaskCollection<CheckForbiddenApis> allCheckForbiddenTasks =
        project.getTasks().withType(CheckForbiddenApis.class);

    project
        .getTasks()
        .named("check")
        .configure(
            task -> {
              task.dependsOn(allCheckForbiddenTasks);
            });

    allCheckForbiddenTasks.configureEach(
        task -> {
          task.getSuppressAnnotations().add("**.SuppressForbidden");

          task.getBundledSignatures()
              .addAll(Set.of("jdk-unsafe", "jdk-deprecated", "jdk-internal", "jdk-non-portable"));

          switch (task.getName()) {
            case "forbiddenApisMain":
              task.getBundledSignatures().add("jdk-system-out");
              break;
            case "forbiddenApisTest":
              break;
          }

          if (!task.getName().startsWith("forbiddenApis")) {
            throw reportError(
                "forbidden-apis-task-awkwardly-named",
                "Expect all forbidden-apis task to be named forbiddenApisXYZ,"
                    + "where XYZ is the sourceSet's compile classpath configuration to inspect: "
                    + task.getName());
          }

          // Figure out the source set and configuration name.
          var sourceSetName = task.getName().replaceAll("forbiddenApis", "");
          sourceSetName =
              sourceSetName.substring(0, 1).toLowerCase(Locale.ROOT) + sourceSetName.substring(1);

          var compileConfiguration =
              sourceSets.named(sourceSetName).get().getCompileClasspathConfigurationName();

          // Figure out the dependencies.

          var signatureFiles =
              project
                  .getConfigurations()
                  .named(compileConfiguration)
                  .flatMap(conf -> conf.getIncoming().getResolutionResult().getRootComponent())
                  .map(
                      graphRoot -> {
                        return collectAllResolved(graphRoot).stream()
                            .map(
                                rulesFile ->
                                    forbiddenApisDir
                                        .getAsFile()
                                        .get()
                                        .toPath()
                                        .resolve(rulesFile)
                                        .toFile())
                            .filter(
                                file -> {
                                  var exists = Files.exists(file.toPath());
                                  task.getLogger()
                                      .debug(
                                          "Forbidden APIs signature file: {}{}",
                                          file.toPath(),
                                          exists ? " (exists)" : " (does not exist)");
                                  return exists;
                                })
                            .toList();
                      });

          var inputs = project.getLayout().getProjectDirectory().files(signatureFiles);
          task.getInputs().files(inputs);
          task.setSignaturesFiles(task.getSignaturesFiles().plus(inputs));
        });
  }

  private List<String> collectAllResolved(ResolvedComponentResult graphRoot) {
    HashSet<ResolvedDependencyResult> allResolved = new HashSet<>();
    ArrayDeque<DependencyResult> queue = new ArrayDeque<>(graphRoot.getDependencies());

    while (!queue.isEmpty()) {
      var dep = queue.removeFirst();
      if (dep instanceof ResolvedDependencyResult resolvedDep) {
        if (allResolved.add(resolvedDep)) {
          queue.addAll(resolvedDep.getSelected().getDependencies());
        }
      } else {
        throw reportError(
            "forbidden-apis-unresolved-dependency",
            "Unresolved dependency, can't apply forbidden APIs: " + dep);
      }
    }

    return allResolved.stream()
        .map(
            dep -> {
              var moduleVersion = dep.getSelected().getModuleVersion();
              return moduleVersion.getGroup() + "-" + moduleVersion.getName() + ".txt";
            })
        .sorted()
        .toList();
  }
}
