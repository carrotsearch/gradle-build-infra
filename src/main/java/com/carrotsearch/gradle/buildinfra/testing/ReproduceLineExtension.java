package com.carrotsearch.gradle.buildinfra.testing;

import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOption;
import com.carrotsearch.gradle.buildinfra.buildoptions.BuildOptionValueSource;
import java.util.ArrayList;
import java.util.List;
import org.apache.tools.ant.types.Commandline;
import org.gradle.api.tasks.testing.TestDescriptor;

public class ReproduceLineExtension {
  private final String taskPath;
  private final List<String> extraArgs = new ArrayList<>();

  public ReproduceLineExtension(String taskPath) {
    this.taskPath = taskPath;
  }

  public String getGradleReproLine(TestDescriptor descriptor) {
    var args = new ArrayList<String>();
    args.add("./gradlew");
    args.add(taskPath);

    if (descriptor.getClassName() != null) {
      args.add("--tests");
      if (descriptor.isComposite()) {
        args.add(descriptor.getClassName());
      } else {
        args.add(descriptor.getClassName() + "." + descriptor.getName());
      }
    }

    args.addAll(extraArgs);
    return Commandline.toString(args.toArray(new String[0]));
  }

  public void addGradleProperty(String name, String value) {
    extraArgs.add("-P" + name + "=" + value);
  }

  public void addBuildOption(BuildOption buildOption) {
    if (!buildOption.isPresent()) {
      return;
    }

    if (!buildOption.isEqualToDefaultValue()
        || buildOption.getSource() == BuildOptionValueSource.COMPUTED_VALUE) {
      addGradleProperty(buildOption.getName(), buildOption.asStringProvider().get());
    }
  }
}
