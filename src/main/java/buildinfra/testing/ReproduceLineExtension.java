package buildinfra.testing;

import buildinfra.buildoptions.BuildOption;
import buildinfra.buildoptions.BuildOptionValue;
import buildinfra.buildoptions.BuildOptionValueSource;
import org.apache.tools.ant.types.Commandline;
import org.gradle.api.tasks.testing.TestDescriptor;

import java.util.ArrayList;
import java.util.List;

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
    if (!buildOption.getValue().isPresent()) {
      return;
    }

    var opt = buildOption.getValue().get();
    if (!opt.defaultValue() || opt.source() == BuildOptionValueSource.COMPUTED_VALUE) {
      addGradleProperty(buildOption.getName(), opt.value());
    }
  }
}
