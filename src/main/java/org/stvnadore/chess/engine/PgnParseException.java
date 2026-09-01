package org.stvnadore.chess.engine;

import java.io.Serial;

/**
 * Thrown when a PGN file contains malformed header tags, invalid tokens, or unparseable structure.
 */
public class PgnParseException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Constructs a PgnParseException with a detail message.
   *
   * @param message the detail message
   */
  public PgnParseException(String message) {
    super(message);
  }

  /**
   * Constructs a PgnParseException with a detail message and cause.
   *
   * @param message the detail message
   * @param cause the underlying cause
   */
  public PgnParseException(String message, Throwable cause) {
    super(message, cause);
  }
}