package org.stvnadore.chess.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ChessDomainModelTest {

  @Test
  @DisplayName("Square record validates rank bounds and algebraic round-trip")
  void testSquareValidationAndAlgebraicConversion() {
    Square e4 = Square.fromAlgebraic("e4");
    assertEquals(Square.File.E, e4.file());
    assertEquals(4, e4.rank());
    assertEquals("e4", e4.toAlgebraic());

    Square a1 = new Square(Square.File.A, 1);
    assertEquals("a1", a1.toAlgebraic());

    assertThrows(IllegalArgumentException.class, () -> new Square(Square.File.C, 0));
    assertThrows(IllegalArgumentException.class, () -> new Square(Square.File.C, 9));
    assertThrows(IllegalArgumentException.class, () -> Square.fromAlgebraic("z9"));
  }

  @Test
  @DisplayName("Move record validates halfmoves bounds, non-null fields, and PromotionRole")
  void testMoveValidation() {
    Square e2 = Square.fromAlgebraic("e2");
    Square e4 = Square.fromAlgebraic("e4");

    Move move = new Move(e2, e4, Optional.of(Move.PromotionRole.QUEEN), false, 0);
    assertEquals(Move.PromotionRole.QUEEN, move.promotion().orElseThrow());
    assertEquals(0, move.halfmovesSincePawnOrCapture());
    assertFalse(move.isCapture());

    assertThrows(IllegalArgumentException.class, () -> new Move(e2, e4, Optional.empty(), false, -1));
    assertThrows(IllegalArgumentException.class, () -> new Move(e2, e4, Optional.empty(), false, 101));
    assertThrows(NullPointerException.class, () -> new Move(null, e4, Optional.empty(), false, 0));
  }

  @Test
  @DisplayName("TurnState and GameHistory records enforce immutability, bounds, and non-null invariants")
  void testGameHistoryImmutability() {
    Square from = Square.fromAlgebraic("e2");
    Square to = Square.fromAlgebraic("e4");
    Move move = new Move(from, to, Optional.empty(), false, 0);

    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, move, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", 25);
    GameHistory game = new GameHistory("game-1", "WhitePlayer", "BlackPlayer", List.of(turn), Optional.of(GameHistory.TerminalOutcome.WHITE_WIN));

    assertEquals("game-1", game.gameId());
    assertEquals(1, game.turns().size());
    assertEquals(GameHistory.TerminalOutcome.WHITE_WIN, game.result().orElseThrow());
    assertThrows(UnsupportedOperationException.class, () -> game.turns().add(turn));

    assertThrows(IllegalArgumentException.class, () ->
        new TurnState(0, Piece.PieceColor.WHITE, move, "fen", 0));
    assertThrows(IllegalArgumentException.class, () ->
        new TurnState(1, Piece.PieceColor.WHITE, move, "fen", 40000));
  }
}