package org.stvnadore.chess.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class TerminalDetectorTest {

  @Test
  @DisplayName("Scholar's Mate position correctly triggers WHITE_WIN checkmate")
  void testScholarsMateDetection() {
    // Scholar's Mate final position: White Queen on f7 delivers checkmate supported by Bishop c4
    BoardState scholarsMate = FenCodec.parse("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4");
    List<Move> legalMoves = MoveValidator.generateLegalMoves(scholarsMate);

    assertTrue(legalMoves.isEmpty(), "Black must have zero legal moves");
    assertTrue(MoveValidator.isInCheck(scholarsMate, scholarsMate.activeColor()), "Black king must be in check");

    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(scholarsMate, legalMoves);
    assertTrue(outcome.isPresent());
    assertEquals(GameHistory.TerminalOutcome.WHITE_WIN, outcome.get());
  }

  @Test
  @DisplayName("Fool's Mate position correctly triggers BLACK_WIN checkmate")
  void testFoolsMateDetection() {
    // Fool's mate: 1. f3 e5 2. g4 Qh4#
    BoardState foolsMate = FenCodec.parse("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3");
    List<Move> legalMoves = MoveValidator.generateLegalMoves(foolsMate);

    assertTrue(legalMoves.isEmpty(), "White must have zero legal moves");
    assertTrue(MoveValidator.isInCheck(foolsMate, foolsMate.activeColor()), "White king must be in check");

    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(foolsMate, legalMoves);
    assertTrue(outcome.isPresent());
    assertEquals(GameHistory.TerminalOutcome.BLACK_WIN, outcome.get());
  }

  @Test
  @DisplayName("Corner stalemate position correctly triggers DRAW outcome")
  void testCornerStalemateDetection() {
    // Black King on a8, White King on c7, White Queen on b6. Black to move, not in check, no legal moves.
    BoardState stalemate = FenCodec.parse("k7/2K5/1Q6/8/8/8/8/8 b - - 0 1");
    List<Move> legalMoves = MoveValidator.generateLegalMoves(stalemate);

    assertTrue(legalMoves.isEmpty(), "Black must have zero legal moves");
    assertFalse(MoveValidator.isInCheck(stalemate, stalemate.activeColor()), "Black king is NOT in check");

    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(stalemate, legalMoves);
    assertTrue(outcome.isPresent());
    assertEquals(GameHistory.TerminalOutcome.DRAW, outcome.get());
  }

  @Test
  @DisplayName("50-move rule trigger fires when halfmoveClock reaches 100")
  void testFiftyMoveRuleTrigger() {
    BoardState fiftyMoves = FenCodec.parse("8/8/4k3/8/8/4K3/8/8 w - - 100 75");
    List<Move> legalMoves = MoveValidator.generateLegalMoves(fiftyMoves);

    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(fiftyMoves, legalMoves);
    assertTrue(outcome.isPresent());
    assertEquals(GameHistory.TerminalOutcome.DRAW, outcome.get());
  }

  @Test
  @DisplayName("Insufficient material conditions (K vs K, K+N vs K, K+B vs K) trigger DRAW")
  void testInsufficientMaterialDetection() {
    // King vs King
    BoardState kvk = FenCodec.parse("8/8/4k3/8/8/4K3/8/8 w - - 0 1");
    assertTrue(TerminalDetector.isInsufficientMaterial(kvk));
    assertEquals(GameHistory.TerminalOutcome.DRAW,
        TerminalDetector.detectOutcome(kvk, MoveValidator.generateLegalMoves(kvk)).orElseThrow());

    // King + White Knight vs King
    BoardState kvkn = FenCodec.parse("8/8/4k3/8/5N2/4K3/8/8 w - - 0 1");
    assertTrue(TerminalDetector.isInsufficientMaterial(kvkn));

    // King + Black Bishop vs King
    BoardState kvkb = FenCodec.parse("8/8/4k3/2b5/8/4K3/8/8 w - - 0 1");
    assertTrue(TerminalDetector.isInsufficientMaterial(kvkb));
  }
}
