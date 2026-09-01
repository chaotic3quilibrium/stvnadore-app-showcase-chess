package org.stvnadore.chess.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class FenCodecTest {

  @Test
  @DisplayName("Initial starting position FEN parses and formats with exact fidelity")
  void testInitialPositionRoundTrip() {
    String initialFen = BoardState.INITIAL_FEN;
    BoardState board = FenCodec.parse(initialFen);

    assertEquals(Piece.PieceColor.WHITE, board.activeColor());
    assertTrue(board.whiteKingsideCastling());
    assertTrue(board.whiteQueensideCastling());
    assertTrue(board.blackKingsideCastling());
    assertTrue(board.blackQueensideCastling());
    assertTrue(board.enPassantTarget().isEmpty());
    assertEquals(0, board.halfmoveClock());
    assertEquals(1, board.fullmoveNumber());

    // Verify pieces
    assertEquals(new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.ROOK), board.pieceAt(Square.fromAlgebraic("a1")).orElseThrow());
    assertEquals(new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.KING), board.pieceAt(Square.fromAlgebraic("e1")).orElseThrow());
    assertEquals(new Piece(Piece.PieceColor.BLACK, Piece.PieceRole.KING), board.pieceAt(Square.fromAlgebraic("e8")).orElseThrow());

    String formatted = FenCodec.format(board);
    assertEquals(initialFen, formatted);
  }

  @Test
  @DisplayName("Midgame position with en passant target and partial castling parses correctly")
  void testMidgamePositionParsing() {
    String fen = "r1bqk2r/pp1n1ppp/2p1pn2/3p4/2PP4/2N2NP1/PP2PPBP/R1BQK2R b Kkq c3 0 7";
    BoardState board = FenCodec.parse(fen);

    assertEquals(Piece.PieceColor.BLACK, board.activeColor());
    assertTrue(board.whiteKingsideCastling());
    assertFalse(board.whiteQueensideCastling());
    assertTrue(board.blackKingsideCastling());
    assertTrue(board.blackQueensideCastling());
    assertEquals(Square.fromAlgebraic("c3"), board.enPassantTarget().orElseThrow());
    assertEquals(0, board.halfmoveClock());
    assertEquals(7, board.fullmoveNumber());

    String formatted = FenCodec.format(board);
    assertEquals(fen, formatted);
  }

  @Test
  @DisplayName("Position with no castling rights formats with hyphen '-'")
  void testNoCastlingRights() {
    String fen = "8/8/4k3/8/8/4K3/8/8 w - - 45 60";
    BoardState board = FenCodec.parse(fen);

    assertFalse(board.whiteKingsideCastling());
    assertFalse(board.whiteQueensideCastling());
    assertFalse(board.blackKingsideCastling());
    assertFalse(board.blackQueensideCastling());
    assertEquals(45, board.halfmoveClock());
    assertEquals(60, board.fullmoveNumber());

    assertEquals(fen, FenCodec.format(board));
  }

  @Test
  @DisplayName("Malformed FEN inputs fail fast with IllegalArgumentException")
  void testMalformedFenRejection() {
    // 1. Missing fields
    assertThrows(IllegalArgumentException.class, () -> FenCodec.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq"));

    // 2. Invalid number of ranks (7 ranks instead of 8)
    assertThrows(IllegalArgumentException.class, () -> FenCodec.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1"));

    // 3. Rank sum overflow (9 squares in rank 1)
    assertThrows(IllegalArgumentException.class, () -> FenCodec.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR1 w KQkq - 0 1"));

    // 4. Invalid active color
    assertThrows(IllegalArgumentException.class, () -> FenCodec.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1"));

    // 5. Unknown piece character
    assertThrows(IllegalArgumentException.class, () -> FenCodec.parse("rnbqkbnr/pppppppp/8/8/4X3/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"));
  }
}
