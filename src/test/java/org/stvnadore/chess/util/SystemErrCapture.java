package org.stvnadore.chess.util;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoped, thread-safe utility and JUnit 5 extension for intercepting and muting {@code System.err}
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
public final class SystemErrCapture implements AutoCloseable, BeforeEachCallback, AfterEachCallback {

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
   * Executes a callable block while muting {@code System.err}, returning the callable result.
   *
   * @param action executable block
   * @param <T> return type
   * @return result of the action
   * @throws Exception if the action throws
   */
  public static <T> T callWithMutedErr(Callable<T> action) throws Exception {
    try (SystemErrCapture capture = mute()) {
      T result = action.call();
      capture.flush();
      return result;
    }
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

  @Override
  public void beforeEach(ExtensionContext context) {
    System.setErr(capturingPrintStream);
    closed = false;
  }

  @Override
  public void afterEach(ExtensionContext context) {
    close();
  }
}
