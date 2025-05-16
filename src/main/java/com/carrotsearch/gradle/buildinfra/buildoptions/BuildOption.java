package com.carrotsearch.gradle.buildinfra.buildoptions;

import java.util.EnumSet;
import java.util.Locale;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

public abstract class BuildOption implements Named {
  private final Project project;

  public abstract BuildOptionType getType();

  abstract void setType(BuildOptionType type);

  public abstract String getDescription();

  abstract void setDescription(String description);

  abstract Property<BuildOptionValue> getValue();

  abstract Property<BuildOptionValue> getDefaultValue();

  public final Provider<String> asStringProvider() {
    return getValue().map(BuildOptionValue::value);
  }

  @Inject
  public BuildOption(Project project) {
    this.project = project;
  }

  public Provider<Boolean> asBooleanProvider() {
    ensureType(
        BuildOptionType.BOOLEAN, EnumSet.of(BuildOptionType.BOOLEAN, BuildOptionType.STRING));
    return asStringProvider()
        .map(
            value -> {
              String v = value.toLowerCase(Locale.ROOT);
              if (v.equals("true") || v.equals("false")) {
                return Boolean.parseBoolean(v);
              }
              throw new GradleException(
                  String.format(
                      Locale.ROOT,
                      "Build option '%s' is of type %s and expects %s but was: %s",
                      getName(),
                      getType(),
                      "a 'true' or 'false' value",
                      value));
            });
  }

  public Provider<Integer> asIntProvider() {
    ensureType(
        BuildOptionType.INTEGER, EnumSet.of(BuildOptionType.INTEGER, BuildOptionType.STRING));
    return asStringProvider()
        .map(
            value -> {
              try {
                return Integer.parseInt(value);
              } catch (NumberFormatException e) {
                throw new GradleException(
                    String.format(
                        Locale.ROOT,
                        "Build option '%s' is of type %s and expects %s but was: %s",
                        getName(),
                        getType(),
                        "an integer value",
                        value));
              }
            });
  }

  public Provider<Directory> asDirProvider() {
    ensureType(
        BuildOptionType.DIRECTORY, EnumSet.of(BuildOptionType.DIRECTORY, BuildOptionType.STRING));
    return asStringProvider()
        .map(
            value -> {
              return project.getLayout().getProjectDirectory().dir(value);
            });
  }

  public Provider<RegularFile> asFileProvider() {
    ensureType(BuildOptionType.FILE, EnumSet.of(BuildOptionType.FILE, BuildOptionType.STRING));
    return asStringProvider()
        .map(
            value -> {
              return project.getLayout().getProjectDirectory().file(value);
            });
  }

  private void ensureType(BuildOptionType target, EnumSet<BuildOptionType> expected) {
    if (!expected.contains(getType())) {
      throw new GradleException(
          String.format(
              Locale.ROOT,
              "Build option '%s' is of type %s, it cannot be converted to %s.",
              getName(),
              getType(),
              target));
    }
  }

  public final boolean isPresent() {
    return getValue().isPresent();
  }

  public boolean isEqualToDefaultValue() {
    var defValue = getDefaultValue();
    var value = getValue();
    if (value.isPresent() && defValue.isPresent()) {
      return defValue.get().equals(value.get());
    } else {

      return false;
    }
  }

  public BuildOptionValueSource getSource() {
    if (!isPresent()) {
      throw new GradleException("This build option has no value set: " + getName());
    }
    return getValue().get().source();
  }

  String relativePath(Directory value) {
    var projectPath = project.getLayout().getProjectDirectory().getAsFile().toPath();
    var valuePath = value.getAsFile().toPath();
    return projectPath.relativize(valuePath).toString();
  }

  public String relativePath(RegularFile value) {
    var projectPath = project.getLayout().getProjectDirectory().getAsFile().toPath();
    var valuePath = value.getAsFile().toPath();
    return projectPath.relativize(valuePath).toString();
  }
}
