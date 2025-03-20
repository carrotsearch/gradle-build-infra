package buildinfra.testing;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.gradle.api.internal.tasks.testing.logging.FullExceptionFormatter;
import org.gradle.api.internal.tasks.testing.logging.TestExceptionFormatter;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestOutputEvent;
import org.gradle.api.tasks.testing.TestOutputListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.api.tasks.testing.logging.TestLogging;
import org.gradle.internal.logging.text.StyledTextOutput;

/**
 * An error reporting listener that queues test output streams and displays them on failure.
 *
 * <p>Heavily inspired by Elasticsearch's ErrorReportingTestListener (ASL 2.0 licensed).
 */
class ErrorReportingTestListener implements TestOutputListener, TestListener {
  private final Logger taskLogger;
  private final TestExceptionFormatter formatter;
  private final Map<TestKey, OutputHandler> outputHandlers = new ConcurrentHashMap<>();
  private final Path spillDir;
  private final Path outputsDir;
  private final boolean verboseMode;
  private final StyledTextOutput styledOut;
  private final ReproduceLineExtension reproduceLineExtension;

  public ErrorReportingTestListener(
      Logger taskLogger,
      StyledTextOutput styledOut,
      ReproduceLineExtension reproduceLineExtension,
      TestLogging testLogging,
      Path spillDir,
      Path outputsDir,
      boolean verboseMode) {
    this.formatter = new FullExceptionFormatter(testLogging);
    this.spillDir = spillDir;
    this.outputsDir = outputsDir;
    this.verboseMode = verboseMode;
    this.taskLogger = taskLogger;
    this.styledOut = styledOut;
    this.reproduceLineExtension = reproduceLineExtension;
  }

  @Override
  public void onOutput(TestDescriptor testDescriptor, TestOutputEvent outputEvent) {
    handlerFor(testDescriptor).write(outputEvent);
  }

  @Override
  public void beforeSuite(TestDescriptor testDescriptor) {}

  @Override
  public void beforeTest(TestDescriptor testDescriptor) {}

  private static final int WARN_OUTPUT_SIZE_LIMIT = 1024 * 1024 * 10;

  @Override
  public void afterSuite(final TestDescriptor suite, TestResult result) {
    if (suite.getParent() == null || suite.getName().startsWith("Gradle")) {
      return;
    }

    TestKey key = TestKey.of(suite);
    try {
      OutputHandler outputHandler = outputHandlers.get(key);
      if (outputHandler != null) {
        long length = outputHandler.length();
        if (length > WARN_OUTPUT_SIZE_LIMIT) {
          taskLogger.warn(
              String.format(
                  Locale.ROOT,
                  "WARNING: Test %s wrote %,d bytes of output.",
                  suite.getName(),
                  length));
        }
      }

      boolean echoOutput = Objects.equals(result.getResultType(), TestResult.ResultType.FAILURE);

      // If the test suite failed, report output.
      if (echoOutput) {
        Files.createDirectories(outputsDir);
        Path outputLog = outputsDir.resolve(getOutputLogName(suite));

        // Save the output of a failing test to disk.
        try (Writer w = Files.newBufferedWriter(outputLog, StandardCharsets.UTF_8)) {
          if (outputHandler != null) {
            outputHandler.copyTo(w);
          }
        }

        if (!verboseMode) {
          if (!taskLogger.isEnabled(LogLevel.LIFECYCLE)) {
            taskLogger.error(
                suite.getDisplayName() + " > TESTS FAILED\n    Test output at: " + outputLog);
          } else {
            synchronized (styledOut) {
              styledOut.append("\n");
              styledOut
                  .append(suite.getDisplayName())
                  .append(" > ")
                  .style(StyledTextOutput.Style.Failure)
                  .append("TESTS FAILED\n")
                  .style(StyledTextOutput.Style.Normal);

              if (reproduceLineExtension != null) {
                styledOut
                    .append("    reproduce with: ")
                    .style(StyledTextOutput.Style.Success)
                    .append(reproduceLineExtension.getGradleReproLine(suite))
                    .style(StyledTextOutput.Style.Normal)
                    .append("\n");
              }

              styledOut
                  .append("    test suite's output: ")
                  .style(StyledTextOutput.Style.Success)
                  .append(outputLog.toString())
                  .style(StyledTextOutput.Style.Normal);

              if (Files.size(outputLog) > WARN_OUTPUT_SIZE_LIMIT) {
                styledOut.append(
                    " (too large to display here: " + Files.size(outputLog) + " bytes).\n");
              } else {
                styledOut.append(" (copied below):\n");
                styledOut.style(StyledTextOutput.Style.Info);
                styledOut.append(Files.readString(outputLog, StandardCharsets.UTF_8));
              }
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      OutputHandler handler = outputHandlers.remove(key);
      if (handler != null) {
        try {
          handler.close();
        } catch (IOException e) {
          taskLogger.error("Failed to close output handler for: " + key, e);
        }
      }
    }
  }

  private static Pattern SANITIZE = Pattern.compile("[^a-zA-Z .\\-_0-9]+");

  public static String getOutputLogName(TestDescriptor suite) {
    return SANITIZE.matcher("OUTPUT-" + suite.getName() + ".txt").replaceAll("_");
  }

  @Override
  public void afterTest(TestDescriptor testDescriptor, TestResult result) {
    // Include the failure exception stacktrace(s) in the test's output log.
    if (result.getResultType() == TestResult.ResultType.FAILURE) {
      OutputHandler outputHandler = handlerFor(testDescriptor);
      outputHandler.write(
          "Test: "
              + testDescriptor.getClassName()
              + "."
              + testDescriptor.getDisplayName()
              + " FAILED\n");
      if (!result.getExceptions().isEmpty()) {
        String message = formatter.format(testDescriptor, result.getExceptions());
        outputHandler.write("Exception:\n");
        outputHandler.write(message);
      }
      outputHandler.write("\n");
    }
  }

  private OutputHandler handlerFor(TestDescriptor descriptor) {
    // Attach output of leaves (individual tests) to their parent.
    if (!descriptor.isComposite()) {
      descriptor = descriptor.getParent();
    }
    return outputHandlers.computeIfAbsent(TestKey.of(descriptor), (key) -> new OutputHandler());
  }

  public static class TestKey {
    private final String key;

    private TestKey(String key) {
      this.key = key;
    }

    public static TestKey of(TestDescriptor d) {
      StringBuilder key = new StringBuilder();
      key.append(d.getClassName());
      key.append("::");
      key.append(d.getName());
      key.append("::");
      key.append(d.getParent() == null ? "-" : d.getParent().toString());
      return new TestKey(key.toString());
    }

    @Override
    public boolean equals(Object o) {
      return o != null && o.getClass() == this.getClass() && Objects.equals(((TestKey) o).key, key);
    }

    @Override
    public int hashCode() {
      return key.hashCode();
    }

    @Override
    public String toString() {
      return key;
    }
  }

  private class OutputHandler implements Closeable {
    // Max single-line buffer before automatic wrap occurs.
    private static final int MAX_LINE_WIDTH = 1024 * 4;

    private final SpillWriter buffer;

    // internal stream.
    private final PrefixedWriter sint;
    // stdout
    private final PrefixedWriter sout;
    // stderr
    private final PrefixedWriter serr;

    // last used stream (so that we can flush it properly and prefixes are not screwed up).
    private PrefixedWriter last;

    public OutputHandler() {
      buffer =
          new SpillWriter(
              () -> {
                try {
                  Files.createDirectories(spillDir);
                  return Files.createTempFile(spillDir, "spill-", ".tmp");
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });

      Writer sink = buffer;
      if (verboseMode) {
        sink = new StdOutTeeWriter(buffer);
      }

      sint = new PrefixedWriter("   > ", sink, MAX_LINE_WIDTH);
      sout = new PrefixedWriter("  1> ", sink, MAX_LINE_WIDTH);
      serr = new PrefixedWriter("  2> ", sink, MAX_LINE_WIDTH);
      last = sint;
    }

    public void write(TestOutputEvent event) {
      write(
          (event.getDestination() == TestOutputEvent.Destination.StdOut ? sout : serr),
          event.getMessage());
    }

    public void write(String message) {
      write(sint, message);
    }

    public long length() throws IOException {
      return buffer.length();
    }

    private void write(PrefixedWriter out, String message) {
      try {
        if (out != last) {
          last.completeLine();
          last = out;
        }
        out.write(message);
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to write to test output.", e);
      }
    }

    public void copyTo(Writer out) throws IOException {
      flush();
      buffer.copyTo(out);
    }

    public void flush() throws IOException {
      sout.completeLine();
      serr.completeLine();
      buffer.flush();
    }

    @Override
    public void close() throws IOException {
      buffer.close();
    }
  }
}
