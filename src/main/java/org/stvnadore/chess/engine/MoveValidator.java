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
 * Validates candidate moves, evaluates square attack status, and applies state transitions.
 */
public final class MoveValidator {

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

  private MoveValidator() {
    // Utility class
  }

  /**
   * Generates all strictly legal moves for the active color on the given board.
   *
   * @param board current board state
   * @return filtered list of strictly legal moves
   */
  public static List<Move> generateLegalMoves(BoardState board) {
    Objects.requireNonNull(board, "board must not be null");
    List<Move> pseudoMoves = MoveGenerator.generatePseudoLegalMoves(board);
    List<Move> legalMoves = new ArrayList<>(pseudoMoves.size());
    Piece.PieceColor activeColor = board.activeColor();
    Piece.PieceColor opponentColor = (activeColor == Piece.PieceColor.WHITE)
        ? Piece.PieceColor.BLACK : Piece.PieceColor.WHITE;

    for (Move move : pseudoMoves) {
      // Castling transit safety check
      if (isCastlingMove(board, move)) {
        if (!isCastlingPathSafe(board, move, opponentColor)) {
          continue;
        }
      }

      // Simulate move and verify moving player's king is safe
      BoardState nextBoard = applyMove(board, move);
      if (!isInCheck(nextBoard, activeColor)) {
        legalMoves.add(move);
      }
    }

    return legalMoves;
  }

  /**
   * Checks whether the king of the specified color is currently under attack.
   *
   * @param board current board position
   * @param kingColor player color to test
   * @return true if king is in check
   */
  public static boolean isInCheck(BoardState board, Piece.PieceColor kingColor) {
    Objects.requireNonNull(board, "board must not be null");
    Objects.requireNonNull(kingColor, "kingColor must not be null");

    Square kingSquare = findKingSquare(board, kingColor);
    Piece.PieceColor attackerColor = (kingColor == Piece.PieceColor.WHITE)
        ? Piece.PieceColor.BLACK : Piece.PieceColor.WHITE;

    return isSquareAttacked(board, kingSquare, attackerColor);
  }

  /**
   * Determines if a target square is attacked by any piece of the specified attacker color.
   *
   * @param board current board state
   * @param square target square
   * @param attackerColor attacking player color
   * @return true if target square is attacked
   */
  public static boolean isSquareAttacked(BoardState board, Square square, Piece.PieceColor attackerColor) {
    Objects.requireNonNull(board, "board must not be null");
    Objects.requireNonNull(square, "square must not be null");
    Objects.requireNonNull(attackerColor, "attackerColor must not be null");

    int fileIdx = square.file().ordinal();
    int rankIdx = square.rank() - 1;

    // 1. Pawn Attacks
    int pawnDir = (attackerColor == Piece.PieceColor.WHITE) ? -1 : 1; // Reverse direction to find attacker
    int pawnRank = rankIdx + pawnDir;
    if (pawnRank >= 0 && pawnRank <= 7) {
      for (int pawnFileOffset : new int[]{-1, 1}) {
        int pawnFile = fileIdx + pawnFileOffset;
        if (pawnFile >= 0 && pawnFile <= 7) {
          @Nullable Piece p = board.pieceAtIndex(pawnRank * 8 + pawnFile);
          if (p != null && p.color() == attackerColor && p.role() == Piece.PieceRole.PAWN) {
            return true;
          }
        }
      }
    }

    // 2. Knight Attacks
    for (int[] offset : KNIGHT_OFFSETS) {
      int tf = fileIdx + offset[0];
      int tr = rankIdx + offset[1];
      if (tf >= 0 && tf <= 7 && tr >= 0 && tr <= 7) {
        @Nullable Piece p = board.pieceAtIndex(tr * 8 + tf);
        if (p != null && p.color() == attackerColor && p.role() == Piece.PieceRole.KNIGHT) {
          return true;
        }
      }
    }

    // 3. Bishop & Queen Diagonal Rays
    if (checkRayAttacks(board, fileIdx, rankIdx, BISHOP_DIRECTIONS, attackerColor, Piece.PieceRole.BISHOP)) {
      return true;
    }

    // 4. Rook & Queen Orthogonal Rays
    if (checkRayAttacks(board, fileIdx, rankIdx, ROOK_DIRECTIONS, attackerColor, Piece.PieceRole.ROOK)) {
      return true;
    }

    // 5. King Proximity Attacks
    for (int[] offset : KING_OFFSETS) {
      int tf = fileIdx + offset[0];
      int tr = rankIdx + offset[1];
      if (tf >= 0 && tf <= 7 && tr >= 0 && tr <= 7) {
        @Nullable Piece p = board.pieceAtIndex(tr * 8 + tf);
        if (p != null && p.color() == attackerColor && p.role() == Piece.PieceRole.KING) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Applies an executed move to an immutable BoardState and computes the successor BoardState.
   *
   * @param board current board state
   * @param move move to apply
   * @return successor board state
   */
  public static BoardState applyMove(BoardState board, Move move) {
    Objects.requireNonNull(board, "board must not be null");
    Objects.requireNonNull(move, "move must not be null");

    Piece[] nextSquares = board.squares().clone();
    Piece movingPiece = board.pieceAt(move.from())
        .orElseThrow(() -> new IllegalArgumentException("No piece at source square: " + move.from()));

    int fromIdx = move.from().toIndex();
    int toIdx = move.to().toIndex();

    // 1. Clear source square
    nextSquares[fromIdx] = null;

    // 2. Handle pawn promotion or standard movement
    if (move.promotion().isPresent()) {
      Piece.PieceRole promoRole = switch (move.promotion().get()) {
        case QUEEN -> Piece.PieceRole.QUEEN;
        case ROOK -> Piece.PieceRole.ROOK;
        case BISHOP -> Piece.PieceRole.BISHOP;
        case KNIGHT -> Piece.PieceRole.KNIGHT;
      };
      nextSquares[toIdx] = new Piece(movingPiece.color(), promoRole);
    } else {
      nextSquares[toIdx] = movingPiece;
    }

    // 3. Handle En Passant capture execution
    if (movingPiece.role() == Piece.PieceRole.PAWN &&
        board.enPassantTarget().isPresent() &&
        board.enPassantTarget().get().equals(move.to())) {
      int capturedPawnRank = move.from().rank(); // Captured pawn is on the same rank as source
      int capturedPawnIdx = (capturedPawnRank - 1) * 8 + move.to().file().ordinal();
      nextSquares[capturedPawnIdx] = null;
    }

    // 4. Handle Castling rook repositioning
    if (movingPiece.role() == Piece.PieceRole.KING && Math.abs(move.from().file().ordinal() - move.to().file().ordinal()) == 2) {
      if (move.to().equals(Square.fromAlgebraic("g1"))) { // White Kingside
        nextSquares[Square.fromAlgebraic("h1").toIndex()] = null;
        nextSquares[Square.fromAlgebraic("f1").toIndex()] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.ROOK);
      } else if (move.to().equals(Square.fromAlgebraic("c1"))) { // White Queenside
        nextSquares[Square.fromAlgebraic("a1").toIndex()] = null;
        nextSquares[Square.fromAlgebraic("d1").toIndex()] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.ROOK);
      } else if (move.to().equals(Square.fromAlgebraic("g8"))) { // Black Kingside
        nextSquares[Square.fromAlgebraic("h8").toIndex()] = null;
        nextSquares[Square.fromAlgebraic("f8").toIndex()] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.ROOK);
      } else if (move.to().equals(Square.fromAlgebraic("c8"))) { // Black Queenside
        nextSquares[Square.fromAlgebraic("a8").toIndex()] = null;
        nextSquares[Square.fromAlgebraic("d8").toIndex()] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.ROOK);
      }
    }

    // 5. Update Castling Rights
    boolean wK = board.whiteKingsideCastling();
    boolean wQ = board.whiteQueensideCastling();
    boolean bK = board.blackKingsideCastling();
    boolean bQ = board.blackQueensideCastling();

    if (movingPiece.role() == Piece.PieceRole.KING) {
      if (movingPiece.color() == Piece.PieceColor.WHITE) { wK = false; wQ = false; }
      else { bK = false; bQ = false; }
    }
    if (move.from().equals(Square.fromAlgebraic("a1")) || move.to().equals(Square.fromAlgebraic("a1"))) wQ = false;
    if (move.from().equals(Square.fromAlgebraic("h1")) || move.to().equals(Square.fromAlgebraic("h1"))) wK = false;
    if (move.from().equals(Square.fromAlgebraic("a8")) || move.to().equals(Square.fromAlgebraic("a8"))) bQ = false;
    if (move.from().equals(Square.fromAlgebraic("h8")) || move.to().equals(Square.fromAlgebraic("h8"))) bK = false;

    // 6. Update En Passant Target
    Optional<Square> newEpTarget = Optional.empty();
    if (movingPiece.role() == Piece.PieceRole.PAWN && Math.abs(move.from().rank() - move.to().rank()) == 2) {
      int middleRank = (move.from().rank() + move.to().rank()) / 2;
      newEpTarget = Optional.of(Square.fromCoords(move.from().file().ordinal(), middleRank - 1));
    }

    // 7. Update Clocks
    int nextHalfmove = (movingPiece.role() == Piece.PieceRole.PAWN || move.isCapture())
        ? 0 : Math.min(board.halfmoveClock() + 1, 100);
    int nextFullmove = (board.activeColor() == Piece.PieceColor.BLACK)
        ? board.fullmoveNumber() + 1 : board.fullmoveNumber();

    Piece.PieceColor nextColor = (board.activeColor() == Piece.PieceColor.WHITE)
        ? Piece.PieceColor.BLACK : Piece.PieceColor.WHITE;

    return new BoardState(nextSquares, nextColor, wK, wQ, bK, bQ, newEpTarget, nextHalfmove, nextFullmove);
  }

  private static boolean checkRayAttacks(BoardState board, int fileIdx, int rankIdx, int[][] directions,
                                         Piece.PieceColor attackerColor, Piece.PieceRole primaryRole) {
    for (int[] dir : directions) {
      int step = 1;
      while (true) {
        int tf = fileIdx + (dir[0] * step);
        int tr = rankIdx + (dir[1] * step);
        if (tf < 0 || tf > 7 || tr < 0 || tr > 7) break;

        @Nullable Piece p = board.pieceAtIndex(tr * 8 + tf);
        if (p != null) {
          if (p.color() == attackerColor && (p.role() == primaryRole || p.role() == Piece.PieceRole.QUEEN)) {
            return true;
          }
          break; // Ray blocked by piece
        }
        step++;
      }
    }
    return false;
  }

  private static boolean isCastlingMove(BoardState board, Move move) {
    Optional<Piece> p = board.pieceAt(move.from());
    return p.isPresent() && p.get().role() == Piece.PieceRole.KING &&
        Math.abs(move.from().file().ordinal() - move.to().file().ordinal()) == 2;
  }

  private static boolean isCastlingPathSafe(BoardState board, Move move, Piece.PieceColor attackerColor) {
    if (move.to().equals(Square.fromAlgebraic("g1"))) {
      return !isSquareAttacked(board, Square.fromAlgebraic("e1"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("f1"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("g1"), attackerColor);
    } else if (move.to().equals(Square.fromAlgebraic("c1"))) {
      return !isSquareAttacked(board, Square.fromAlgebraic("e1"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("d1"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("c1"), attackerColor);
    } else if (move.to().equals(Square.fromAlgebraic("g8"))) {
      return !isSquareAttacked(board, Square.fromAlgebraic("e8"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("f8"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("g8"), attackerColor);
    } else if (move.to().equals(Square.fromAlgebraic("c8"))) {
      return !isSquareAttacked(board, Square.fromAlgebraic("e8"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("d8"), attackerColor) &&
          !isSquareAttacked(board, Square.fromAlgebraic("c8"), attackerColor);
    }
    return true;
  }

  private static Square findKingSquare(BoardState board, Piece.PieceColor kingColor) {
    for (int idx = 0; idx < 64; idx++) {
      @Nullable Piece p = board.pieceAtIndex(idx);
      if (p != null && p.color() == kingColor && p.role() == Piece.PieceRole.KING) {
        return Square.fromIndex(idx);
      }
    }
    throw new IllegalStateException("Board is missing King for color: " + kingColor);
  }
}
