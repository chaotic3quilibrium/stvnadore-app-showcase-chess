package org.stvnadore.chess.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoped, thread-safe utility for intercepting and muting {@code System.err}
 * during negative test execution.
 *
 * <p>Usage as try-with-resources:
 * <pre>{@code
 * try (SystemErrCapture capture = SystemErrCapture.mute()) {
 *   int code = ChessCliApplication.execute(new String[]{"unknown_cmd"});
 *   assertEquals(1, code);
 *   capture.assertContains("Unknown command: unknown_cmd");
 * }
 * }</pre>
 */
public final class SystemErrCapture implements AutoCloseable {

  private final PrintStream originalErr;
  private final ByteArrayOutputStream buffer;
  private final PrintStream capturingPrintStream;
  private boolean closed;

  /**
   * Creates a new instance capturing standard error from the point of creation.
   */
  public SystemErrCapture() {
    this.originalErr = System.err;
    this.buffer = new ByteArrayOutputStream();
    this.capturingPrintStream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
    this.closed = false;
  }

  /**
   * Begins intercepting {@code System.err}, redirecting all output to an in-memory buffer.
   *
   * @return active {@code SystemErrCapture} instance to be closed upon completion
   */
  public static SystemErrCapture mute() {
    SystemErrCapture capture = new SystemErrCapture();
    System.setErr(capture.capturingPrintStream);
    return capture;
  }


  /**
   * Flushes the underlying capture print stream buffer.
   */
  public void flush() {
    capturingPrintStream.flush();
  }

  /**
   * Returns the complete text written to {@code System.err} since interception began.
   *
   * @return intercepted standard error text
   */
  public String getCapturedText() {
    flush();
    return buffer.toString(StandardCharsets.UTF_8);
  }

  /**
   * Asserts that the captured standard error contains the expected substring.
   *
   * @param expectedSubstring substring expected in stderr
   */
  public void assertContains(String expectedSubstring) {
    Objects.requireNonNull(expectedSubstring, "expectedSubstring must not be null");
    String text = getCapturedText();
    assertTrue(
        text.contains(expectedSubstring),
        () -> "Expected System.err to contain: '" + expectedSubstring + "', but received: '" + text + "'"
    );
  }

  @Override
  public void close() {
    if (!closed) {
      try {
        flush();
      } finally {
        System.setErr(originalErr);
        closed = true;
      }
    }
  }
}
