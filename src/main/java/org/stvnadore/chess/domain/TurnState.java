package org.stvnadore.chess.domain;

import java.util.Objects;

/**
 * Immutable domain record capturing the state after a turn is executed.
 *
 * @param turnNumber 1-based turn sequence number
 * @param activeColor the player color who executed the move
 * @param move the executed move
 * @param fen the resulting Forsyth-Edwards Notation board state string
 * @param evaluationCentipawns engine evaluation score in centipawns
 */
public record TurnState(
    long turnNumber,
    Piece.PieceColor activeColor,
    Move move,
    String fen,
    int evaluationCentipawns
) {

  /**
   * Validates turn state bounds and invariants.
   */
  public TurnState {
    Objects.requireNonNull(activeColor, "activeColor must not be null");
    Objects.requireNonNull(move, "move must not be null");
    Objects.requireNonNull(fen, "fen must not be null");
    if (turnNumber < 1) {
      throw new IllegalArgumentException("turnNumber must be positive (>= 1): " + turnNumber);
    }
    if (evaluationCentipawns < Short.MIN_VALUE || evaluationCentipawns > Short.MAX_VALUE) {
      throw new IllegalArgumentException("evaluationCentipawns must fit in Int16: " + evaluationCentipawns);
    }
  }
}