package com.carrotsearch.gradle.buildinfra.buildoptions;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

public abstract class BuildOptionsExtension {
  public abstract NamedDomainObjectContainer<BuildOption> getAllOptions();

  /** Returns a lazy provider for the given option. */
  public Provider<String> optionValue(String name) {
    return getAllOptions().named(name).flatMap(BuildOption::asStringProvider);
  }

  public BuildOption getOption(String name) {
    return getAllOptions().named(name).get();
  }

  public boolean hasOption(String name) {
    return getAllOptions().findByName(name) != null;
  }

  public Provider<String> getAt(String name) {
    return optionValue(name);
  }

  public Provider<String> propertyMissing(String name) {
    return optionValue(name);
  }

  private Provider<String> newStringOption(
      String name, String description, Action<BuildOption> spec) {
    return getAllOptions()
        .create(
            name,
            opt -> {
              opt.setDescription(description);
              opt.setType(BuildOptionType.STRING);
              spec.execute(opt);
            })
        .asStringProvider();
  }

  /** Build option with the default value. */
  public Provider<String> addOption(String name, String description, String defaultValue) {
    return newStringOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(new BuildOptionValue(defaultValue, BuildOptionValueSource.EXPLICIT_VALUE));
        });
  }

  /** Build option with some dynamically computed value. */
  public Provider<String> addOption(
      String name, String description, Provider<String> defaultValueProvider) {
    return newStringOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  defaultValueProvider.map(
                      value -> new BuildOptionValue(value, BuildOptionValueSource.COMPUTED_VALUE)));
        });
  }

  /** Build option without any default value. */
  public Provider<String> addOption(String name, String description) {
    return newStringOption(name, description, opt -> {});
  }

  private Provider<Boolean> newBooleanOption(
      String name, String description, Action<BuildOption> spec) {
    return getAllOptions()
        .create(
            name,
            opt -> {
              opt.setDescription(description);
              opt.setType(BuildOptionType.BOOLEAN);
              spec.execute(opt);
            })
        .asBooleanProvider();
  }

  /** Build option with the default value. */
  public Provider<Boolean> addBooleanOption(String name, String description, boolean defaultValue) {
    return newBooleanOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  new BuildOptionValue(
                      Boolean.toString(defaultValue), BuildOptionValueSource.EXPLICIT_VALUE));
        });
  }

  /** Build option with some dynamically computed value. */
  public Provider<Boolean> addBooleanOption(
      String name, String description, Provider<Boolean> defaultValueProvider) {
    return newBooleanOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  defaultValueProvider.map(
                      value ->
                          new BuildOptionValue(
                              Boolean.toString(value), BuildOptionValueSource.COMPUTED_VALUE)));
        });
  }

  /** Build option without any default value. */
  public Provider<Boolean> addBooleanOption(String name, String description) {
    return newBooleanOption(name, description, opt -> {});
  }

  private Provider<Integer> newIntOption(
      String name, String description, Action<BuildOption> spec) {
    return getAllOptions()
        .create(
            name,
            opt -> {
              opt.setDescription(description);
              opt.setType(BuildOptionType.INTEGER);
              spec.execute(opt);
            })
        .asIntProvider();
  }

  /** Build option with the default value. */
  public Provider<Integer> addIntOption(String name, String description, int defaultValue) {
    return newIntOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  new BuildOptionValue(
                      Integer.toString(defaultValue), BuildOptionValueSource.EXPLICIT_VALUE));
        });
  }

  /** Build option with some dynamically computed value. */
  public Provider<Integer> addIntOption(
      String name, String description, Provider<Integer> defaultValueProvider) {
    return newIntOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  defaultValueProvider.map(
                      value ->
                          new BuildOptionValue(
                              Integer.toString(value), BuildOptionValueSource.COMPUTED_VALUE)));
        });
  }

  /** Build option without any default value. */
  public Provider<Integer> addIntOption(String name, String description) {
    return newIntOption(name, description, opt -> {});
  }

  private Provider<Directory> newDirOption(
      String name, String description, Action<BuildOption> spec) {
    return getAllOptions()
        .create(
            name,
            opt -> {
              opt.setDescription(description);
              opt.setType(BuildOptionType.DIRECTORY);
              spec.execute(opt);
            })
        .asDirProvider();
  }

  /** Build option without any default value. */
  public Provider<Directory> addDirOption(String name, String description) {
    return newDirOption(name, description, opt -> {});
  }

  /** Build option with the default value. */
  public Provider<Directory> addDirOption(String name, String description, Directory defaultValue) {
    return newDirOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  new BuildOptionValue(
                      opt.relativePath(defaultValue), BuildOptionValueSource.EXPLICIT_VALUE));
        });
  }

  /** Build option with some dynamically computed value. */
  public Provider<Directory> addDirOption(
      String name, String description, Provider<Directory> defaultValueProvider) {
    return newDirOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  defaultValueProvider.map(
                      value ->
                          new BuildOptionValue(
                              opt.relativePath(value), BuildOptionValueSource.COMPUTED_VALUE)));
        });
  }

  private Provider<RegularFile> newFileOption(
      String name, String description, Action<BuildOption> spec) {
    return getAllOptions()
        .create(
            name,
            opt -> {
              opt.setDescription(description);
              opt.setType(BuildOptionType.FILE);
              spec.execute(opt);
            })
        .asFileProvider();
  }

  /** Build option without any default value. */
  public Provider<RegularFile> addFileOption(String name, String description) {
    return newFileOption(name, description, opt -> {});
  }

  /** Build option with the default value. */
  public Provider<RegularFile> addFileOption(
      String name, String description, RegularFile defaultValue) {
    return newFileOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  new BuildOptionValue(
                      opt.relativePath(defaultValue), BuildOptionValueSource.EXPLICIT_VALUE));
        });
  }

  /** Build option with some dynamically computed value. */
  public Provider<RegularFile> addFileOption(
      String name, String description, Provider<RegularFile> defaultValueProvider) {
    return newFileOption(
        name,
        description,
        opt -> {
          opt.getDefaultValue()
              .set(
                  defaultValueProvider.map(
                      value ->
                          new BuildOptionValue(
                              opt.relativePath(value), BuildOptionValueSource.COMPUTED_VALUE)));
        });
  }
}
