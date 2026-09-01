package org.stvnadore.chess.engine;

import org.jspecify.annotations.Nullable;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generates pseudo-legal chess moves for all piece types according to geometric movement rules.
 */
public final class MoveGenerator {

  private static final int[][] KNIGHT_OFFSETS = {
      {1, 2}, {2, 1}, {2, -1}, {1, -2},
      {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}
  };

  private static final int[][] BISHOP_DIRECTIONS = {
      {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  private static final int[][] ROOK_DIRECTIONS = {
      {1, 0}, {-1, 0}, {0, 1}, {0, -1}
  };

  private static final int[][] KING_OFFSETS = {
      {1, 0}, {-1, 0}, {0, 1}, {0, -1},
      {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  private MoveGenerator() {
    // Utility class
  }

  /**
   * Generates all pseudo-legal moves for the active color on the given board.
   *
   * @param board current board state
   * @return list of pseudo-legal moves
   */
  public static List<Move> generatePseudoLegalMoves(BoardState board) {
    Objects.requireNonNull(board, "board must not be null");
    List<Move> moves = new ArrayList<>(64);
    Piece.PieceColor activeColor = board.activeColor();

    for (int idx = 0; idx < 64; idx++) {
      @Nullable Piece piece = board.pieceAtIndex(idx);
      if (piece == null || piece.color() != activeColor) {
        continue;
      }
      Square from = Square.fromIndex(idx);
      switch (piece.role()) {
        case PAWN -> generatePawnMoves(board, from, piece.color(), moves);
        case KNIGHT -> generateLeapMoves(board, from, piece.color(), KNIGHT_OFFSETS, moves);
        case BISHOP -> generateRayMoves(board, from, piece.color(), BISHOP_DIRECTIONS, moves);
        case ROOK -> generateRayMoves(board, from, piece.color(), ROOK_DIRECTIONS, moves);
        case QUEEN -> {
          generateRayMoves(board, from, piece.color(), BISHOP_DIRECTIONS, moves);
          generateRayMoves(board, from, piece.color(), ROOK_DIRECTIONS, moves);
        }
        case KING -> generateKingMoves(board, from, piece.color(), moves);
      }
    }

    return moves;
  }

  private static void generatePawnMoves(BoardState board, Square from, Piece.PieceColor color, List<Move> moves) {
    int direction = (color == Piece.PieceColor.WHITE) ? 1 : -1;
    int startRank = (color == Piece.PieceColor.WHITE) ? 2 : 7;
    int promoRank = (color == Piece.PieceColor.WHITE) ? 8 : 1;

    int fromFile = from.file().ordinal();
    int fromRank = from.rank();

    // 1. Single forward advance
    int oneStepRank = fromRank + direction;
    if (oneStepRank >= 1 && oneStepRank <= 8) {
      Square to = Square.fromCoords(fromFile, oneStepRank - 1);
      if (board.pieceAt(to).isEmpty()) {
        if (to.rank() == promoRank) {
          addPromotionMoves(from, to, false, board.halfmoveClock(), moves);
        } else {
          moves.add(new Move(from, to, Optional.empty(), false, 0));
        }

        // 2. Double forward advance
        if (fromRank == startRank) {
          int twoStepRank = fromRank + (2 * direction);
          Square twoStepTo = Square.fromCoords(fromFile, twoStepRank - 1);
          if (board.pieceAt(twoStepTo).isEmpty()) {
            moves.add(new Move(from, twoStepTo, Optional.empty(), false, 0));
          }
        }
      }
    }

    // 3. Diagonal Captures & En Passant
    int[] captureFileOffsets = {-1, 1};
    for (int offset : captureFileOffsets) {
      int targetFile = fromFile + offset;
      int targetRank = fromRank + direction;
      if (targetFile >= 0 && targetFile <= 7 && targetRank >= 1 && targetRank <= 8) {
        Square targetSquare = Square.fromCoords(targetFile, targetRank - 1);
        Optional<Piece> targetPiece = board.pieceAt(targetSquare);

        // Standard capture
        if (targetPiece.isPresent() && targetPiece.get().color() != color) {
          if (targetSquare.rank() == promoRank) {
            addPromotionMoves(from, targetSquare, true, board.halfmoveClock(), moves);
          } else {
            moves.add(new Move(from, targetSquare, Optional.empty(), true, 0));
          }
        }

        // En passant capture
        if (board.enPassantTarget().isPresent() && board.enPassantTarget().get().equals(targetSquare)) {
          moves.add(new Move(from, targetSquare, Optional.empty(), true, 0));
        }
      }
    }
  }

  private static void addPromotionMoves(Square from, Square to, boolean isCapture, int currentHalfmoves, List<Move> moves) {
    for (Move.PromotionRole promo : Move.PromotionRole.values()) {
      moves.add(new Move(from, to, Optional.of(promo), isCapture, 0));
    }
  }

  private static void generateLeapMoves(BoardState board, Square from, Piece.PieceColor color, int[][] offsets, List<Move> moves) {
    int fileIdx = from.file().ordinal();
    int rankIdx = from.rank() - 1;

    for (int[] offset : offsets) {
      int targetFile = fileIdx + offset[0];
      int targetRank = rankIdx + offset[1];

      if (targetFile >= 0 && targetFile <= 7 && targetRank >= 0 && targetRank <= 7) {
        Square to = Square.fromCoords(targetFile, targetRank);
        Optional<Piece> targetPiece = board.pieceAt(to);
        if (targetPiece.isEmpty()) {
          int nextHalfmoves = Math.min(board.halfmoveClock() + 1, 100);
          moves.add(new Move(from, to, Optional.empty(), false, nextHalfmoves));
        } else if (targetPiece.get().color() != color) {
          moves.add(new Move(from, to, Optional.empty(), true, 0));
        }
      }
    }
  }

  private static void generateRayMoves(BoardState board, Square from, Piece.PieceColor color, int[][] directions, List<Move> moves) {
    int fileIdx = from.file().ordinal();
    int rankIdx = from.rank() - 1;

    for (int[] dir : directions) {
      int step = 1;
      while (true) {
        int targetFile = fileIdx + (dir[0] * step);
        int targetRank = rankIdx + (dir[1] * step);

        if (targetFile < 0 || targetFile > 7 || targetRank < 0 || targetRank > 7) {
          break;
        }

        Square to = Square.fromCoords(targetFile, targetRank);
        Optional<Piece> targetPiece = board.pieceAt(to);
        if (targetPiece.isEmpty()) {
          int nextHalfmoves = Math.min(board.halfmoveClock() + 1, 100);
          moves.add(new Move(from, to, Optional.empty(), false, nextHalfmoves));
        } else {
          if (targetPiece.get().color() != color) {
            moves.add(new Move(from, to, Optional.empty(), true, 0));
          }
          break; // Obstruction encountered; terminate ray
        }
        step++;
      }
    }
  }

  private static void generateKingMoves(BoardState board, Square from, Piece.PieceColor color, List<Move> moves) {
    // Normal single-square king steps
    generateLeapMoves(board, from, color, KING_OFFSETS, moves);

    // Castling moves (Path vacancy check only; attack status checked in MoveValidator)
    if (color == Piece.PieceColor.WHITE && from.equals(Square.fromAlgebraic("e1"))) {
      if (board.whiteKingsideCastling() &&
          board.pieceAt(Square.fromAlgebraic("f1")).isEmpty() &&
          board.pieceAt(Square.fromAlgebraic("g1")).isEmpty()) {
        int nextHalfmoves = Math.min(board.halfmoveClock() + 1, 100);
        moves.add(new Move(from, Square.fromAlgebraic("g1"), Optional.empty(), false, nextHalfmoves));
      }
      if (board.whiteQueensideCastling() &&
          board.pieceAt(Square.fromAlgebraic("d1")).isEmpty() &&
          board.pieceAt(Square.fromAlgebraic("c1")).isEmpty() &&
          board.pieceAt(Square.fromAlgebraic("b1")).isEmpty()) {
        int nextHalfmoves = Math.min(board.halfmoveClock() + 1, 100);
        moves.add(new Move(from, Square.fromAlgebraic("c1"), Optional.empty(), false, nextHalfmoves));
      }
    } else if (color == Piece.PieceColor.BLACK && from.equals(Square.fromAlgebraic("e8"))) {
      if (board.blackKingsideCastling() &&
          board.pieceAt(Square.fromAlgebraic("f8")).isEmpty() &&
          board.pieceAt(Square.fromAlgebraic("g8")).isEmpty()) {
        int nextHalfmoves = Math.min(board.halfmoveClock() + 1, 100);
        moves.add(new Move(from, Square.fromAlgebraic("g8"), Optional.empty(), false, nextHalfmoves));
      }
      if (board.blackQueensideCastling() &&
          board.pieceAt(Square.fromAlgebraic("d8")).isEmpty() &&
          board.pieceAt(Square.fromAlgebraic("c8")).isEmpty() &&
          board.pieceAt(Square.fromAlgebraic("b8")).isEmpty()) {
        int nextHalfmoves = Math.min(board.halfmoveClock() + 1, 100);
        moves.add(new Move(from, Square.fromAlgebraic("c8"), Optional.empty(), false, nextHalfmoves));
      }
    }
  }
}
