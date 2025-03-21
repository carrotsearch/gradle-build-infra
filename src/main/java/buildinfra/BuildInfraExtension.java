package buildinfra;

import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Property;

public abstract class BuildInfraExtension implements ExtensionAware {
  public static final String NAME = "buildinfra";

  /**
   * @return Returns a property indicating we're running under IntelliJ IDEA.
   */
  public abstract Property<Boolean> getIntelliJIdea();
}
