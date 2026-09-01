package org.stvnadore.chess.domain;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable domain record representing a complete chess board position snapshot.
 *
 * @param squares 64-element array of piece positions (null indicates unoccupied square)
 * @param activeColor the player whose turn it is to move
 * @param whiteKingsideCastling true if white can castle kingside
 * @param whiteQueensideCastling true if white can castle queenside
 * @param blackKingsideCastling true if black can castle kingside
 * @param blackQueensideCastling true if black can castle queenside
 * @param enPassantTarget target square for en-passant capture if pawn just advanced 2 squares
 * @param halfmoveClock plies since last pawn push or capture (for 50-move rule)
 * @param fullmoveNumber turn sequence counter starting at 1
 */
public record BoardState(
    @Nullable Piece[] squares,
    Piece.PieceColor activeColor,
    boolean whiteKingsideCastling,
    boolean whiteQueensideCastling,
    boolean blackKingsideCastling,
    boolean blackQueensideCastling,
    Optional<Square> enPassantTarget,
    int halfmoveClock,
    int fullmoveNumber
) {

  /** Standard FEN string representing the initial starting position of a chess match. */
  public static final String INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  /**
   * Compact constructor enforcing non-null fields, array bounds, and defensive copying.
   */
  public BoardState {
    Objects.requireNonNull(squares, "squares must not be null");
    Objects.requireNonNull(activeColor, "activeColor must not be null");
    Objects.requireNonNull(enPassantTarget, "enPassantTarget must not be null");
    if (squares.length != 64) {
      throw new IllegalArgumentException("Board squares array must have length 64, got: " + squares.length);
    }
    if (halfmoveClock < 0 || halfmoveClock > 100) {
      throw new IllegalArgumentException("halfmoveClock must be between 0 and 100 inclusive: " + halfmoveClock);
    }
    if (fullmoveNumber < 1) {
      throw new IllegalArgumentException("fullmoveNumber must be >= 1: " + fullmoveNumber);
    }
    squares = squares.clone();
  }

  /**
   * Retrieves the piece at a given square.
   *
   * @param square board coordinate
   * @return optional piece
   */
  public Optional<Piece> pieceAt(Square square) {
    Objects.requireNonNull(square, "square must not be null");
    return Optional.ofNullable(squares[square.toIndex()]);
  }

  /**
   * Retrieves the piece at a given 0..63 index.
   *
   * @param index coordinate index
   * @return piece or null if unoccupied
   */
  public @Nullable Piece pieceAtIndex(int index) {
    return squares[index];
  }

  /**
   * Creates an initial standard chess starting board state.
   *
   * @return starting BoardState
   */
  public static BoardState initial() {
    Piece[] sq = new Piece[64];

    // White pieces (Rank 1 & 2)
    sq[0] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.ROOK);
    sq[1] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.KNIGHT);
    sq[2] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.BISHOP);
    sq[3] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.QUEEN);
    sq[4] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.KING);
    sq[5] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.BISHOP);
    sq[6] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.KNIGHT);
    sq[7] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.ROOK);
    for (int i = 8; i < 16; i++) {
      sq[i] = new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.PAWN);
    }

    // Black pieces (Rank 7 & 8)
    for (int i = 48; i < 56; i++) {
      sq[i] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.PAWN);
    }
    sq[56] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.ROOK);
    sq[57] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.KNIGHT);
    sq[58] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.BISHOP);
    sq[59] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.QUEEN);
    sq[60] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.KING);
    sq[61] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.BISHOP);
    sq[62] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.KNIGHT);
    sq[63] = new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.ROOK);

    return new BoardState(sq, Piece.PieceColor.WHITE, true, true, true, true, Optional.empty(), 0, 1);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BoardState that)) return false;
    return whiteKingsideCastling == that.whiteKingsideCastling &&
        whiteQueensideCastling == that.whiteQueensideCastling &&
        blackKingsideCastling == that.blackKingsideCastling &&
        blackQueensideCastling == that.blackQueensideCastling &&
        halfmoveClock == that.halfmoveClock &&
        fullmoveNumber == that.fullmoveNumber &&
        Arrays.equals(squares, that.squares) &&
        activeColor == that.activeColor &&
        enPassantTarget.equals(that.enPassantTarget);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(activeColor, whiteKingsideCastling, whiteQueensideCastling,
        blackKingsideCastling, blackQueensideCastling, enPassantTarget, halfmoveClock, fullmoveNumber);
    result = 31 * result + Arrays.hashCode(squares);
    return result;
  }
}
