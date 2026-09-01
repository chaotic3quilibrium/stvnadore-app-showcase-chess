package org.stvnadore.chess.engine;

import org.jspecify.annotations.Nullable;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Detects terminal match conditions (checkmate, stalemate, 50-move rule, insufficient material).
 */
public final class TerminalDetector {

  private TerminalDetector() {
    // Utility class
  }

  /**
   * Evaluates current board state and legal moves to determine terminal outcome.
   *
   * @param board current board state
   * @param legalMoves list of strictly legal moves available to active player
   * @return optional terminal outcome (empty if match is ongoing)
   */
  public static Optional<GameHistory.TerminalOutcome> detectOutcome(BoardState board, List<Move> legalMoves) {
    Objects.requireNonNull(board, "board must not be null");
    Objects.requireNonNull(legalMoves, "legalMoves must not be null");

    // 1. Checkmate or Stalemate when no legal moves remain
    if (legalMoves.isEmpty()) {
      boolean inCheck = MoveValidator.isInCheck(board, board.activeColor());
      if (inCheck) {
        // Active color is checkmated; opponent wins
        return Optional.of(board.activeColor() == Piece.PieceColor.WHITE
            ? GameHistory.TerminalOutcome.BLACK_WIN
            : GameHistory.TerminalOutcome.WHITE_WIN);
      } else {
        // Stalemate
        return Optional.of(GameHistory.TerminalOutcome.DRAW);
      }
    }

    // 2. Fifty-Move Rule (100 halfmoves)
    if (board.halfmoveClock() >= 100) {
      return Optional.of(GameHistory.TerminalOutcome.DRAW);
    }

    // 3. Insufficient Material Check
    if (isInsufficientMaterial(board)) {
      return Optional.of(GameHistory.TerminalOutcome.DRAW);
    }

    return Optional.empty();
  }

  /**
   * Tests for standard FIDE insufficient material conditions:
   * - K vs K
   * - K+B vs K
   * - K+N vs K
   * - K+B vs K+B with bishops on same color squares
   *
   * @param board current board state position
   * @return true if neither side has sufficient mating material
   */
  public static boolean isInsufficientMaterial(BoardState board) {
    int whitePieces = 0;
    int blackPieces = 0;
    int whiteKnights = 0;
    int blackKnights = 0;
    int whiteBishops = 0;
    int blackBishops = 0;

    for (int idx = 0; idx < 64; idx++) {
      @Nullable Piece p = board.pieceAtIndex(idx);
      if (p == null) continue;

      if (p.role() == Piece.PieceRole.PAWN || p.role() == Piece.PieceRole.ROOK || p.role() == Piece.PieceRole.QUEEN) {
        return false; // Pawns, rooks, queens can deliver checkmate
      }

      if (p.color() == Piece.PieceColor.WHITE) {
        whitePieces++;
        if (p.role() == Piece.PieceRole.KNIGHT) whiteKnights++;
        if (p.role() == Piece.PieceRole.BISHOP) whiteBishops++;
      } else {
        blackPieces++;
        if (p.role() == Piece.PieceRole.KNIGHT) blackKnights++;
        if (p.role() == Piece.PieceRole.BISHOP) blackBishops++;
      }
    }

    // King vs King
    if (whitePieces == 1 && blackPieces == 1) return true;

    // King + Minor Piece vs King
    if ((whitePieces == 2 && whiteKnights + whiteBishops == 1 && blackPieces == 1) ||
        (blackPieces == 2 && blackKnights + blackBishops == 1 && whitePieces == 1)) {
      return true;
    }

    return false;
  }
}
