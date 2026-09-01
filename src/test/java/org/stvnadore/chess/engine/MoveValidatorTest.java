package org.stvnadore.chess.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class MoveValidatorTest {

  @Test
  @DisplayName("Absolute pin prevents piece from moving off the pin line")
  void testAbsolutePinFilter() {
    // White King on e1, White Knight on e2, Black Rook on e8
    BoardState pinnedKnightBoard = FenCodec.parse("4r3/8/8/8/8/8/4N3/4K2k w - - 0 1");
    List<Move> legalMoves = MoveValidator.generateLegalMoves(pinnedKnightBoard);

    // Knight cannot move anywhere because it is pinned along the e-file
    boolean knightCanMove = legalMoves.stream()
        .anyMatch(m -> m.from().equals(Square.fromAlgebraic("e2")));
    assertFalse(knightCanMove, "Pinned knight must not have any legal moves");

    // King must be able to move
    boolean kingCanMove = legalMoves.stream()
        .anyMatch(m -> m.from().equals(Square.fromAlgebraic("e1")));
    assertTrue(kingCanMove, "King must have legal evasion moves");
  }

  @Test
  @DisplayName("Castling is rejected if king is in check or transits an attacked square")
  void testCastlingSafetyValidation() {
    // 1. Transit square f1 attacked by black rook on f8
    BoardState transitAttacked = FenCodec.parse("5r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1");
    // e1 is in check from f8? No, f8 attacks f1, not e1.
    // e1 to g1 requires transit across f1.
    assertFalse(MoveValidator.isInCheck(transitAttacked, Piece.PieceColor.WHITE));
    List<Move> legalMoves = MoveValidator.generateLegalMoves(transitAttacked);

    boolean canCastleKingside = legalMoves.stream()
        .anyMatch(m -> m.from().equals(Square.fromAlgebraic("e1")) && m.to().equals(Square.fromAlgebraic("g1")));
    assertFalse(canCastleKingside, "King cannot castle through attacked square f1");

    boolean canCastleQueenside = legalMoves.stream()
        .anyMatch(m -> m.from().equals(Square.fromAlgebraic("e1")) && m.to().equals(Square.fromAlgebraic("c1")));
    assertTrue(canCastleQueenside, "Queenside castling is legal since d1, c1, b1 are safe");

    // 2. Castling while in check is illegal
    BoardState inCheck = FenCodec.parse("4r2k/8/8/8/8/8/8/R3K2R w KQ - 0 1");
    assertTrue(MoveValidator.isInCheck(inCheck, Piece.PieceColor.WHITE));
    List<Move> inCheckLegalMoves = MoveValidator.generateLegalMoves(inCheck);

    boolean castleWhileInCheck = inCheckLegalMoves.stream()
        .anyMatch(m -> m.from().equals(Square.fromAlgebraic("e1")) &&
            (m.to().equals(Square.fromAlgebraic("g1")) || m.to().equals(Square.fromAlgebraic("c1"))));
    assertFalse(castleWhileInCheck, "King cannot castle while in check");
  }

  @Test
  @DisplayName("En passant execution removes the captured pawn from the board")
  void testEnPassantExecution() {
    // White pawn on e5, Black pawn on d5, ep target d6
    BoardState epBoard = FenCodec.parse("8/8/8/3pP3/8/8/8/4K2k w - d6 0 1");
    Move epMove = new Move(Square.fromAlgebraic("e5"), Square.fromAlgebraic("d6"), Optional.empty(), true, 0);

    BoardState nextBoard = MoveValidator.applyMove(epBoard, epMove);

    // Source e5 must be empty
    assertTrue(nextBoard.pieceAt(Square.fromAlgebraic("e5")).isEmpty());
    // Destination d6 must have White Pawn
    assertEquals(new Piece(Piece.PieceColor.WHITE, Piece.PieceRole.PAWN), nextBoard.pieceAt(Square.fromAlgebraic("d6")).orElseThrow());
    // Captured pawn on d5 must be REMOVED (empty)
    assertTrue(nextBoard.pieceAt(Square.fromAlgebraic("d5")).isEmpty());
    // En passant target must reset to empty
    assertTrue(nextBoard.enPassantTarget().isEmpty());
    // Active color flipped to Black
    assertEquals(Piece.PieceColor.BLACK, nextBoard.activeColor());
  }

  @Test
  @DisplayName("Discovered check via horizontal en-passant capture is rejected as illegal")
  void testEnPassantDiscoveredCheckRejection() {
    // White King on a5, White Pawn on d5, Black Pawn on e5 (ep target e6), Black Rook on h5
    BoardState discoveredCheckEp = FenCodec.parse("8/8/8/K2Pp2r/8/8/8/7k w - e6 0 1");
    List<Move> legalMoves = MoveValidator.generateLegalMoves(discoveredCheckEp);

    boolean epMoveAllowed = legalMoves.stream()
        .anyMatch(m -> m.from().equals(Square.fromAlgebraic("d5")) && m.to().equals(Square.fromAlgebraic("e6")));
    assertFalse(epMoveAllowed, "En passant that opens rank 5 to discovered rook check must be rejected");
  }

  @Test
  @DisplayName("Moving king revokes castling rights and moving rook revokes wing castling")
  void testCastlingRightsRevocation() {
    BoardState initial = BoardState.initial();

    // White moves King e1-e2
    Move kingMove = new Move(Square.fromAlgebraic("e1"), Square.fromAlgebraic("e2"), Optional.empty(), false, 1);
    BoardState afterKingMove = MoveValidator.applyMove(initial, kingMove);

    assertFalse(afterKingMove.whiteKingsideCastling());
    assertFalse(afterKingMove.whiteQueensideCastling());
    assertTrue(afterKingMove.blackKingsideCastling());
    assertTrue(afterKingMove.blackQueensideCastling());

    // White moves Rook h1-g1
    Move rookMove = new Move(Square.fromAlgebraic("h1"), Square.fromAlgebraic("g1"), Optional.empty(), false, 1);
    BoardState afterRookMove = MoveValidator.applyMove(initial, rookMove);

    assertFalse(afterRookMove.whiteKingsideCastling());
    assertTrue(afterRookMove.whiteQueensideCastling());
  }
}
