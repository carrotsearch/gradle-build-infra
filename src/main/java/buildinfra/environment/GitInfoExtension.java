package buildinfra.environment;

import org.gradle.api.provider.MapProperty;

public abstract class GitInfoExtension {
  public static final String NAME = "git-info";

  public abstract MapProperty<String, String> getGitInfo();
}
