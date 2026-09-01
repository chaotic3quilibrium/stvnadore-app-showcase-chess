package org.stvnadore.chess.domain;

import java.util.Objects;

/**
 * Immutable domain record representing a chess piece on the board.
 *
 * @param color the piece color (WHITE or BLACK)
 * @param role the piece role (PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING)
 */
public record Piece(PieceColor color, PieceRole role) {

  /**
   * Validates non-null invariants.
   */
  public Piece {
    Objects.requireNonNull(color, "color must not be null");
    Objects.requireNonNull(role, "role must not be null");
  }

  /**
   * Piece player color.
   */
  public enum PieceColor {
    /** White player pieces. */
    WHITE,
    /** Black player pieces. */
    BLACK
  }

  /**
   * Piece type role.
   */
  public enum PieceRole {
    /** Pawn piece. */
    PAWN,
    /** Knight piece. */
    KNIGHT,
    /** Bishop piece. */
    BISHOP,
    /** Rook piece. */
    ROOK,
    /** Queen piece. */
    QUEEN,
    /** King piece. */
    KING
  }
}