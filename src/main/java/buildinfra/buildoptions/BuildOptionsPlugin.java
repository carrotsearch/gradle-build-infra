package buildinfra.buildoptions;

import buildinfra.AbstractPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.gradle.api.Describable;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

/**
 * A plugin providing {@code buildOptions} extension with overrideable key-value options that may
 * affect the build. For example, a random {@code tests.seed} or {@code tests.filter}.
 */
public class BuildOptionsPlugin extends AbstractPlugin {
  public static final String LOCAL_BUILD_OPTIONS_FILE = ".local-options.properties";
  public static final String OPTIONS_EXTENSION_NAME = "buildOptions";

  @Inject
  public BuildOptionsPlugin(Problems problems) {
    super(problems);
  }

  public abstract static class LocalOptionOverrideValueSource
      implements ValueSource<String, LocalOptionOverrideValueSource.Parameters>, Describable {

    @Nullable
    public String obtain() {
      return getParameters().getValue().getOrNull();
    }

    public String getDisplayName() {
      return String.format(
          "local override of '%s' in %s",
          getParameters().getName().get(), LOCAL_BUILD_OPTIONS_FILE);
    }

    public abstract static class Parameters implements ValueSourceParameters {
      abstract Property<String> getValue();

      abstract Property<String> getName();
    }
  }

  @Override
  public void apply(Project project) {
    BuildOptionsExtension options = project.getObjects().newInstance(BuildOptionsExtension.class);
    project.getExtensions().add(OPTIONS_EXTENSION_NAME, options);

    var localOptionsFile =
        project.getRootProject().getLayout().getProjectDirectory().file(LOCAL_BUILD_OPTIONS_FILE);
    Map<String, String> localOptions = new TreeMap<>();
    if (localOptionsFile.getAsFile().exists()) {
      try (var is = Files.newInputStream(localOptionsFile.getAsFile().toPath())) {
        var v = new Properties();
        v.load(is);
        v.stringPropertyNames().forEach(key -> localOptions.put(key, v.getProperty(key)));
      } catch (IOException e) {
        throw new GradleException("Can't read the local " + LOCAL_BUILD_OPTIONS_FILE + " file.", e);
      }
    }

    options
        .getAllOptions()
        .whenObjectAdded(
            option -> {
              var providers = project.getProviders();
              var optionName = option.getName();
              option
                  .getValue()
                  .convention(
                      providers
                          .systemProperty(optionName)
                          .map(
                              v ->
                                  new BuildOptionValue(
                                      v, false, BuildOptionValueSource.SYSTEM_PROPERTY))
                          .orElse(
                              providers
                                  .gradleProperty(optionName)
                                  .map(
                                      v ->
                                          new BuildOptionValue(
                                              v, false, BuildOptionValueSource.GRADLE_PROPERTY)))
                          .orElse(
                              providers
                                  .environmentVariable(optionName)
                                  .map(
                                      v ->
                                          new BuildOptionValue(
                                              v,
                                              false,
                                              BuildOptionValueSource.ENVIRONMENT_VARIABLE)))
                          .orElse(
                              providers
                                  .of(
                                      LocalOptionOverrideValueSource.class,
                                      valueSource -> {
                                        valueSource.getParameters().getName().set(optionName);
                                        if (localOptions.containsKey(optionName)) {
                                          valueSource
                                              .getParameters()
                                              .getValue()
                                              .set(localOptions.get(optionName));
                                        }
                                      })
                                  .map(
                                      v ->
                                          new BuildOptionValue(
                                              v, false, BuildOptionValueSource.LOCAL_OPTIONS_FILE)))
                          .orElse(option.getDefaultValue()));
            });

    project.getTasks().register(BuildOptionsTask.NAME, BuildOptionsTask.class);
  }
}
