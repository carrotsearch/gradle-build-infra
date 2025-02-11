package buildinfra.buildoptions;

import org.gradle.api.Named;
import org.gradle.api.provider.Property;

public abstract class BuildOption implements Named {
  public abstract Property<BuildOptionValue> getValue();

  abstract Property<BuildOptionValue> getDefaultValue();

  public abstract String getDescription();

  abstract void setDescription(String description);
}
