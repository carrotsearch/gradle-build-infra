package com.carrotsearch.gradle.buildinfra.buildoptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutput.Style;
import org.gradle.internal.logging.text.StyledTextOutputFactory;

/** Displays a list of all build options and their current values declared in the project. */
public abstract class BuildOptionsTask extends DefaultTask {
  public static final String BUILD_OPTIONS_TASK_GROUP = "Build options";
  public static final String NAME = "buildOptions";

  private final String projectName;

  static final Style normal = Style.Normal;
  static final Style computed = Style.Identifier;
  static final Style overridden = Style.FailureHeader;
  static final Style comment = Style.ProgressStatus;

  @Inject
  protected abstract StyledTextOutputFactory getOutputFactory();

  @Input
  public NamedDomainObjectContainer<BuildOption> getAllBuildOptions() {
    var optsExtension = getProject().getExtensions().getByType(BuildOptionsExtension.class);
    return optsExtension.getAllOptions();
  }

  @Inject
  public BuildOptionsTask(Project project) {
    setDescription("Shows configurable options");
    setGroup(BUILD_OPTIONS_TASK_GROUP);
    this.projectName =
        (project == project.getRootProject() ? ": (the root project)" : project.getPath());
  }

  /** Option grouping spec. */
  private final OptionGroupingSpec groupingSpec = new OptionGroupingSpec();

  private static record OptionGroup(Pattern matcher, String description) {}

  public final class OptionGroupingSpec {
    private List<OptionGroup> optionGroups = new ArrayList<>();
    private String ungroupedDescription = "Other options:";

    public void group(String description, String regexp) {
      optionGroups.add(new OptionGroup(Pattern.compile(regexp), description));
    }

    public void allOtherOptions(String description) {
      this.ungroupedDescription = description;
    }
  }

  /** Configures option grouping. */
  public void optionGroups(Action<OptionGroupingSpec> action) {
    action.execute(groupingSpec);
  }

  @TaskAction
  public void exec() {
    var out = getOutputFactory().create(this.getClass());

    out.append("Configurable build options in ")
        .withStyle(Style.Identifier)
        .append(projectName)
        .append("\n\n");

    final int keyWidth =
        getAllBuildOptions().stream().mapToInt(opt -> opt.getName().length()).max().orElse(1);
    final String keyFmt = "%-" + keyWidth + "s = ";

    var sortedOptions =
        getAllBuildOptions().stream().sorted(Comparator.comparing(BuildOption::getName)).toList();

    if (groupingSpec.optionGroups.isEmpty()) {
      sortedOptions.forEach(opt -> printOptionInfo(opt, out, keyFmt));
    } else {
      var ungrouped = new LinkedHashSet<>(sortedOptions);
      for (OptionGroup group : groupingSpec.optionGroups) {
        var matchingOptions =
            sortedOptions.stream()
                .filter(opt -> group.matcher.matcher(opt.getName()).matches())
                .toList();

        if (matchingOptions.isEmpty()) {
          continue;
        }

        printOptionGroupHeader(out, group.description);
        for (var opt : matchingOptions) {
          printOptionInfo(opt, out, keyFmt);
          ungrouped.remove(opt);
        }
        out.println();
      }

      if (!ungrouped.isEmpty() && groupingSpec.ungroupedDescription != null) {
        printOptionGroupHeader(out, groupingSpec.ungroupedDescription);
        for (var opt : ungrouped) {
          printOptionInfo(opt, out, keyFmt);
        }
      }
    }

    out.println();
    printLegend(out);
  }

  private static void printOptionInfo(BuildOption opt, StyledTextOutput out, String keyFmt) {
    var value = opt.getValue();

    String valueSource = null;
    var valueStyle = normal;
    if (!value.isPresent()) {
      valueStyle = comment;
    } else {
      var optionValue = value.get();
      if (optionValue.source() == BuildOptionValueSource.COMPUTED_VALUE) {
        valueStyle = computed;
        valueSource = "computed value";
      } else if (!opt.isEqualToDefaultValue()) {
        valueStyle = overridden;
        valueSource =
            switch (optionValue.source()) {
              case GRADLE_PROPERTY -> "project property";
              case SYSTEM_PROPERTY -> "system property";
              case ENVIRONMENT_VARIABLE -> "environment variable";
              case EXPLICIT_VALUE -> "explicit value";
              case COMPUTED_VALUE -> throw new RuntimeException("Unreachable");
              case BUILD_OPTIONS_FILE -> BuildOptionsPlugin.BUILD_OPTIONS_FILE + " file";
              case LOCAL_BUILD_OPTIONS_FILE ->
                  BuildOptionsPlugin.LOCAL_BUILD_OPTIONS_FILE + " file";
            };
      }
    }

    out.format(keyFmt, opt.getName());
    out.withStyle(valueStyle).format("%-8s", value.isPresent() ? value.get() : "[empty]");
    out.withStyle(comment).append(" # ");
    if (valueSource != null || opt.getType() != BuildOptionType.STRING) {
      StringBuilder sb = new StringBuilder();
      if (opt.getType() != BuildOptionType.STRING) {
        sb.append("type: ").append(opt.getType().toString().toLowerCase(Locale.ROOT));
      }
      if (valueSource != null) {
        if (!sb.isEmpty()) sb.append(", ");
        sb.append("source: ").append(valueSource);
      }

      out.withStyle(valueStyle).append("(").append(String.valueOf(sb)).append(") ");
    }
    out.withStyle(comment).append(opt.getDescription());
    out.append("\n");
  }

  private void printOptionGroupHeader(StyledTextOutput out, String description) {
    out.withStyle(normal).append(description);
    out.println();
  }

  private static void printLegend(StyledTextOutput out) {
    out.append("Option value colors legend: ");
    out.withStyle(normal).append("default value");
    out.append(", ");
    out.withStyle(computed).append("computed value");
    out.append(", ");
    out.withStyle(overridden).append("overridden value");
    out.append(", ");
    out.withStyle(comment).append("no value");
    out.println();
  }
}
