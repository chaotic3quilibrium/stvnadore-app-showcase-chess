package org.stvnadore.chess.engine;

import org.jspecify.annotations.Nullable;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;

import java.util.Objects;
import java.util.Optional;

/**
 * Lossless bidirectional parser and serializer for Forsyth-Edwards Notation (FEN).
 */
public final class FenCodec {

  private FenCodec() {
    // Utility class
  }

  /**
   * Parses a standard 6-field FEN string into an immutable BoardState.
   *
   * @param fen standard FEN string
   * @return parsed BoardState
   */
  public static BoardState parse(String fen) {
    Objects.requireNonNull(fen, "fen string must not be null");
    String[] parts = fen.trim().split("\\s+");
    if (parts.length != 6) {
      throw new IllegalArgumentException("FEN must contain exactly 6 fields, found " + parts.length + ": " + fen);
    }

    String piecePlacement = parts[0];
    String activeColorStr = parts[1];
    String castlingRights = parts[2];
    String enPassantStr = parts[3];
    String halfmoveStr = parts[4];
    String fullmoveStr = parts[5];

    // 1. Parse Piece Placement
    Piece[] squares = new Piece[64];
    String[] ranks = piecePlacement.split("/");
    if (ranks.length != 8) {
      throw new IllegalArgumentException("FEN piece placement must contain 8 ranks, found: " + ranks.length);
    }

    for (int r = 0; r < 8; r++) {
      String rankStr = ranks[r];
      int rankIndex = 7 - r; // FEN begins at rank 8 down to rank 1
      int fileIndex = 0;

      for (int i = 0; i < rankStr.length(); i++) {
        char c = rankStr.charAt(i);
        if (Character.isDigit(c)) {
          int emptyCount = c - '0';
          fileIndex += emptyCount;
        } else {
          if (fileIndex >= 8) {
            throw new IllegalArgumentException("Rank " + (rankIndex + 1) + " exceeds 8 squares in FEN: " + fen);
          }
          Piece piece = charToPiece(c);
          int squareIndex = rankIndex * 8 + fileIndex;
          squares[squareIndex] = piece;
          fileIndex++;
        }
      }
      if (fileIndex != 8) {
        throw new IllegalArgumentException("Rank " + (rankIndex + 1) + " does not sum to 8 squares: " + rankStr);
      }
    }

    // 2. Parse Active Color
    Piece.PieceColor activeColor;
    if ("w".equalsIgnoreCase(activeColorStr)) {
      activeColor = Piece.PieceColor.WHITE;
    } else if ("b".equalsIgnoreCase(activeColorStr)) {
      activeColor = Piece.PieceColor.BLACK;
    } else {
      throw new IllegalArgumentException("Invalid active color field: " + activeColorStr);
    }

    // 3. Parse Castling Rights
    boolean wK = castlingRights.contains("K");
    boolean wQ = castlingRights.contains("Q");
    boolean bK = castlingRights.contains("k");
    boolean bQ = castlingRights.contains("q");

    // 4. Parse En-Passant Target
    Optional<Square> epTarget = Optional.empty();
    if (!"-".equals(enPassantStr)) {
      epTarget = Optional.of(Square.fromAlgebraic(enPassantStr));
    }

    // 5. Parse Clocks
    int halfmove = Integer.parseInt(halfmoveStr);
    int fullmove = Integer.parseInt(fullmoveStr);

    return new BoardState(squares, activeColor, wK, wQ, bK, bQ, epTarget, halfmove, fullmove);
  }

  /**
   * Formats an immutable BoardState into a standard 6-field FEN string.
   *
   * @param state board position snapshot
   * @return standard FEN string
   */
  public static String format(BoardState state) {
    Objects.requireNonNull(state, "state must not be null");
    StringBuilder sb = new StringBuilder();

    // 1. Piece Placement
    for (int r = 7; r >= 0; r--) {
      int emptyCount = 0;
      for (int f = 0; f < 8; f++) {
        int idx = r * 8 + f;
        @Nullable Piece piece = state.pieceAtIndex(idx);
        if (piece == null) {
          emptyCount++;
        } else {
          if (emptyCount > 0) {
            sb.append(emptyCount);
            emptyCount = 0;
          }
          sb.append(pieceToChar(piece));
        }
      }
      if (emptyCount > 0) {
        sb.append(emptyCount);
      }
      if (r > 0) {
        sb.append('/');
      }
    }

    // 2. Active Color
    sb.append(' ').append(state.activeColor() == Piece.PieceColor.WHITE ? 'w' : 'b');

    // 3. Castling Rights
    sb.append(' ');
    boolean hasCastling = false;
    if (state.whiteKingsideCastling()) { sb.append('K'); hasCastling = true; }
    if (state.whiteQueensideCastling()) { sb.append('Q'); hasCastling = true; }
    if (state.blackKingsideCastling()) { sb.append('k'); hasCastling = true; }
    if (state.blackQueensideCastling()) { sb.append('q'); hasCastling = true; }
    if (!hasCastling) {
      sb.append('-');
    }

    // 4. En Passant
    sb.append(' ');
    if (state.enPassantTarget().isPresent()) {
      sb.append(state.enPassantTarget().get().toAlgebraic());
    } else {
      sb.append('-');
    }

    // 5. Halfmove Clock & Fullmove Number
    sb.append(' ').append(state.halfmoveClock());
    sb.append(' ').append(state.fullmoveNumber());

    return sb.toString();
  }

  private static Piece charToPiece(char c) {
    Piece.PieceColor color = Character.isUpperCase(c) ? Piece.PieceColor.WHITE : Piece.PieceColor.BLACK;
    char lower = Character.toLowerCase(c);
    Piece.PieceRole role = switch (lower) {
      case 'p' -> Piece.PieceRole.PAWN;
      case 'n' -> Piece.PieceRole.KNIGHT;
      case 'b' -> Piece.PieceRole.BISHOP;
      case 'r' -> Piece.PieceRole.ROOK;
      case 'q' -> Piece.PieceRole.QUEEN;
      case 'k' -> Piece.PieceRole.KING;
      default -> throw new IllegalArgumentException("Unknown piece character in FEN: " + c);
    };
    return new Piece(color, role);
  }

  private static char pieceToChar(Piece piece) {
    char c = switch (piece.role()) {
      case PAWN -> 'p';
      case KNIGHT -> 'n';
      case BISHOP -> 'b';
      case ROOK -> 'r';
      case QUEEN -> 'q';
      case KING -> 'k';
    };
    return piece.color() == Piece.PieceColor.WHITE ? Character.toUpperCase(c) : c;
  }
}
