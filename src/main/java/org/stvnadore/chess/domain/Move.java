package org.stvnadore.chess.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable domain record representing a single chess move transition.
 *
 * @param from source square
 * @param to target square
 * @param promotion optional promotion piece role upon pawn promotion
 * @param isCapture true if the move captures an opponent piece
 * @param halfmovesSincePawnOrCapture plies since last pawn move or capture (max 100)
 */
public record Move(
    Square from,
    Square to,
    Optional<PromotionRole> promotion,
    boolean isCapture,
    int halfmovesSincePawnOrCapture
) {

  /**
   * Validates non-null field invariants and numeric bounds.
   */
  public Move {
    Objects.requireNonNull(from, "from square must not be null");
    Objects.requireNonNull(to, "to square must not be null");
    Objects.requireNonNull(promotion, "promotion option must not be null");
    if (halfmovesSincePawnOrCapture < 0 || halfmovesSincePawnOrCapture > 100) {
      throw new IllegalArgumentException(
          "halfmovesSincePawnOrCapture must be between 0 and 100 inclusive: " + halfmovesSincePawnOrCapture);
    }
  }

  /**
   * Eligible piece roles for pawn promotion under FIDE rules.
   */
  public enum PromotionRole {
    /** Knight promotion target. */
    KNIGHT,
    /** Bishop promotion target. */
    BISHOP,
    /** Rook promotion target. */
    ROOK,
    /** Queen promotion target. */
    QUEEN
  }
}