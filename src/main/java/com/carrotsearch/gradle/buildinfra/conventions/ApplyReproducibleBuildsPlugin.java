package com.carrotsearch.gradle.buildinfra.conventions;

import com.carrotsearch.gradle.buildinfra.AbstractPlugin;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.problems.Problems;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

public class ApplyReproducibleBuildsPlugin extends AbstractPlugin {
  @Inject
  public ApplyReproducibleBuildsPlugin(Problems problems) {
    super(problems);
  }

  @Override
  public void apply(Project project) {
    project
        .getTasks()
        .withType(AbstractArchiveTask.class)
        .configureEach(
            task -> {
              task.setPreserveFileTimestamps(false);
              task.setReproducibleFileOrder(true);
              task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
              task.dirPermissions(
                  perm -> {
                    perm.unix(0755);
                  });
              task.filePermissions(
                  perm -> {
                    perm.unix(0644);
                  });
            });
  }
}
