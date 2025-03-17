package buildinfra.testing;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;

class StdOutTeeWriter extends Writer {
  private final Writer delegate;
  private final PrintStream out = System.out;

  public StdOutTeeWriter(Writer delegate) {
    this.delegate = delegate;
  }

  @Override
  public void write(int c) throws IOException {
    delegate.write(c);
    out.write(c);
  }

  @Override
  public void write(char[] cbuf) throws IOException {
    delegate.write(cbuf);
    out.print(cbuf);
  }

  @Override
  public void write(String str) throws IOException {
    delegate.write(str);
    out.print(str);
  }

  @Override
  public void write(String str, int off, int len) throws IOException {
    delegate.write(str, off, len);
    out.append(str, off, len);
  }

  @Override
  public Writer append(CharSequence csq) throws IOException {
    delegate.append(csq);
    out.append(csq);
    return this;
  }

  @Override
  public Writer append(CharSequence csq, int start, int end) throws IOException {
    delegate.append(csq, start, end);
    out.append(csq, start, end);
    return this;
  }

  @Override
  public Writer append(char c) throws IOException {
    delegate.append(c);
    out.append(c);
    return this;
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    delegate.write(cbuf, off, len);
    out.print(new String(cbuf, off, len));
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
    out.flush();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
    // Don't close the actual output.
  }
}
