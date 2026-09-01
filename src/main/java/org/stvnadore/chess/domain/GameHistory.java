package org.stvnadore.chess.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable domain record representing a complete chess game session.
 *
 * @param gameId unique game session identifier
 * @param whitePlayer white player identifier
 * @param blackPlayer black player identifier
 * @param turns ordered sequence of turn state snapshots
 * @param result optional terminal game outcome (empty if match is in progress)
 */
public record GameHistory(
    String gameId,
    String whitePlayer,
    String blackPlayer,
    List<TurnState> turns,
    Optional<TerminalOutcome> result
) {

  /**
   * Validates game history non-null invariants and wraps turns defensively.
   */
  public GameHistory {
    Objects.requireNonNull(gameId, "gameId must not be null");
    Objects.requireNonNull(whitePlayer, "whitePlayer must not be null");
    Objects.requireNonNull(blackPlayer, "blackPlayer must not be null");
    turns = List.copyOf(turns);
    Objects.requireNonNull(result, "result must not be null");
  }

  /**
   * Concluded match terminal outcomes under FIDE rules.
   */
  public enum TerminalOutcome {
    /** White victory (1-0). */
    WHITE_WIN,
    /** Black victory (0-1). */
    BLACK_WIN,
    /** Drawn match (1/2-1/2). */
    DRAW
  }
}