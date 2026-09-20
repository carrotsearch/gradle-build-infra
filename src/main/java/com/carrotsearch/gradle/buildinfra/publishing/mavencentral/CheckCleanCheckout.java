package com.carrotsearch.gradle.buildinfra.publishing.mavencentral;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Verifies the git checkout has no modified or untracked files. */
@DisableCachingByDefault(because = "Checks the state of the git checkout")
public abstract class CheckCleanCheckout extends DefaultTask {
  /**
   * The state of the git checkout.
   *
   * @see com.carrotsearch.gradle.buildinfra.environment.GitInfoExtension#getGitInfo()
   */
  @Internal
  public abstract MapProperty<String, String> getGitInfo();

  @TaskAction
  public void check() {
    var gitInfo = getGitInfo().get();
    if (!"true".equals(gitInfo.get("git.clean"))) {
      throw new GradleException(
          "Seems like your git checkout isn't clean? Can't publish from this state:\n"
              + gitInfo.getOrDefault("git.changed-files", "")
              + gitInfo.getOrDefault("git.error-log", ""));
    }
  }
}
