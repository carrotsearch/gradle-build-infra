package com.carrotsearch.gradle.buildinfra.utils;

import com.carrotsearch.procfork.ForkedProcess;
import com.carrotsearch.procfork.Launcher;
import com.carrotsearch.procfork.ProcessBuilderLauncher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;

public abstract class BackgroundProcessTask extends DefaultTask {
  @Input
  public abstract Property<Boolean> getUseShell();

  @Input
  public abstract Property<String> getExecutable();

  @InputDirectory
  @Optional
  public abstract Property<String> getCwd();

  @Input
  public abstract ListProperty<String> getArgs();

  @Input
  public abstract MapProperty<String, String> getEnv();

  @Input
  public abstract Property<Integer> getMaxWaitSeconds();

  private TaskProvider<Task> stopTask;

  @Internal
  public TaskProvider<Task> getStopTask() {
    return stopTask;
  }

  private Predicate<ForkedProcess> waitCondition;

  @Internal
  public Predicate<ForkedProcess> getWaitCondition() {
    return waitCondition;
  }

  public void setWaitCondition(Predicate<ForkedProcess> waitCondition) {
    this.waitCondition = waitCondition;
  }

  private ForkedProcess forkedProcess;

  @Internal
  public ForkedProcess getForkedProcess() {
    return forkedProcess;
  }

  public BackgroundProcessTask() {
    finalizedBy(stopTask = createStopTask());

    Project project = getProject();
    getCwd()
        .convention(project.getLayout().getBuildDirectory().get().getAsFile().getAbsolutePath());
    getArgs().convention(List.of());
    getUseShell().convention(true);
    getEnv().convention(Map.of());
    getMaxWaitSeconds().convention(10);
  }

  @TaskAction
  public void launch() throws Exception {
    if (!JavaVersion.current().isJava9Compatible()) {
      throw new GradleException("At least Java 9 is required for this task to work.");
    }

    if (forkedProcess != null) {
      throw new RuntimeException("Task already executed?");
    }

    Launcher launcher = new ProcessBuilderLauncher();
    if (getUseShell().getOrElse(true)) launcher.viaShellLauncher();
    launcher.args(getArgs().get().toArray(String[]::new));
    getEnv().getOrElse(Map.of()).forEach(launcher::envvar);

    launcher.executable(Paths.get(getExecutable().get()));

    Path cwdPath = Path.of(getCwd().get());
    Files.createDirectories(cwdPath);
    launcher.cwd(cwdPath);

    getLogger().info("Launching: {} with args: {}", getExecutable().get(), getArgs().get());
    forkedProcess = launcher.execute();

    Instant deadline = Instant.now().plusSeconds(getMaxWaitSeconds().get());
    while (Instant.now().isBefore(deadline)) {
      if (waitCondition.test(forkedProcess)) {
        // Success.
        return;
      }

      // poll, periodically.
      Thread.sleep(500);
      if (!forkedProcess.getProcess().isAlive()) {
        break;
      }
    }

    var tempFile = Files.createTempFile(super.getTemporaryDir().toPath(), "process-", ".out");
    Files.copy(forkedProcess.getProcessOutputFile(), tempFile, StandardCopyOption.REPLACE_EXISTING);
    getLogger()
        .warn("Forked process output:\n{}", Files.readString(tempFile, StandardCharsets.UTF_8));

    throw new GradleException(
        "The launched process died without meeting the required condition: ${executable},"
            + " log file at: ${tempFile}");
  }

  private TaskProvider<Task> createStopTask() {
    final BackgroundProcessTask self = this;

    return getProject()
        .getTasks()
        .register(
            "${name}#stop",
            spec -> {
              spec.onlyIf(task -> self.forkedProcess != null);
              spec.doFirst(
                  (task) -> {
                    task.getLogger().info("Terminating: {}", getExecutable().get());
                    try {
                      self.forkedProcess.close();
                    } catch (IOException e) {
                      throw new GradleException("Problems stopping background task.", e);
                    }
                  });
            });
  }
}
