package buildinfra;

import org.gradle.api.provider.Property;

public abstract class BuildInfraExtension {
  public static final String NAME = "buildInfra";

  /**
   * @return Returns a property indicating we're running under IntelliJ IDEA.
   */
  public abstract Property<Boolean> getIntelliJIdea();
}
