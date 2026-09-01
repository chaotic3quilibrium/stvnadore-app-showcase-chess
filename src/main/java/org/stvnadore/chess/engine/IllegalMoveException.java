package org.stvnadore.chess.engine;

import java.io.Serial;

/**
 * Thrown when a move violates FIDE chess rules, SAN format, or board state invariants.
 */
public class IllegalMoveException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Constructs an IllegalMoveException with a detail message.
   *
   * @param message the detail message
   */
  public IllegalMoveException(String message) {
    super(message);
  }

  /**
   * Constructs an IllegalMoveException with a detail message and cause.
   *
   * @param message the detail message
   * @param cause the underlying cause
   */
  public IllegalMoveException(String message, Throwable cause) {
    super(message, cause);
  }
}