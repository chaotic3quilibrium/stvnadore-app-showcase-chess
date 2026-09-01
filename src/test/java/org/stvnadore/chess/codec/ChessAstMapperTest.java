package org.stvnadore.chess.codec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ChessAstMapperTest {

  private static String schemaContent;

  @BeforeAll
  static void loadSchema() throws Exception {
    try (InputStream is = ChessAstMapperTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is);
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("Lossless bi-directional AST mapping for all PromotionRole variants")
  void testPromotionRoleVariants() {
    for (Move.PromotionRole role : Move.PromotionRole.values()) {
      Move promoMove = new Move(Square.fromAlgebraic("e7"), Square.fromAlgebraic("e8"), Optional.of(role), false, 0);
      TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, promoMove, "fen-promo", 800);
      GameHistory original = new GameHistory("game-promo-" + role, "P1", "P2", List.of(turn), Optional.empty());

      StvnValue ast = ChessAstMapper.toStvnAst(original, schemaContent);
      assertNotNull(ast);

      GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);
      assertEquals(original, reconstructed);
      assertEquals(role, reconstructed.turns().get(0).move().promotion().orElseThrow());
    }
  }

  @Test
  @DisplayName("Lossless bi-directional AST mapping for all TerminalOutcome variants")
  void testTerminalOutcomeVariants() {
    for (GameHistory.TerminalOutcome outcome : GameHistory.TerminalOutcome.values()) {
      Move m = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
      TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, m, "fen", 0);
      GameHistory original = new GameHistory("game-outcome-" + outcome, "W", "B", List.of(turn), Optional.of(outcome));

      StvnValue ast = ChessAstMapper.toStvnAst(original, schemaContent);
      GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);
      assertEquals(original, reconstructed);
      assertEquals(outcome, reconstructed.result().orElseThrow());
    }
  }

  @Test
  @DisplayName("Numeric bounds AST mapping for Halfmoves (0..100) and TurnNumber (1..1023)")
  void testNumericBoundsMapping() {
    Move m0 = new Move(Square.fromAlgebraic("a2"), Square.fromAlgebraic("a3"), Optional.empty(), false, 0);
    TurnState turn1 = new TurnState(1, Piece.PieceColor.WHITE, m0, "fen1", -32768);

    Move m100 = new Move(Square.fromAlgebraic("a7"), Square.fromAlgebraic("a6"), Optional.empty(), false, 100);
    TurnState turn1023 = new TurnState(1023, Piece.PieceColor.BLACK, m100, "fen2", 32767);

    GameHistory original = new GameHistory("game-bounds", "Player \"A\"", "Player \\B\\", List.of(turn1, turn1023), Optional.empty());

    String doc = ChessAstMapper.toStvnDocument(original, schemaContent);
    assertTrue(doc.contains("1023"));
    assertTrue(doc.contains("100"));

    StvnValue ast = StvnCompiler.compile(doc).orElseThrow();
    GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);

    assertEquals(original, reconstructed);
    assertEquals(-32768, reconstructed.turns().get(0).evaluationCentipawns());
    assertEquals(32767, reconstructed.turns().get(1).evaluationCentipawns());
  }

  @Test
  @DisplayName("Special character and string escaping round-trip")
  void testStringEscapingRoundTrip() {
    String trickyName = "Grandmaster \"The Tactician\"\nSpecial\tPlayer\\Expert";
    Move m = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, m, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", 20);

    GameHistory original = new GameHistory("match/2026/08#1", trickyName, "Standard Opponent", List.of(turn), Optional.empty());
    StvnValue ast = ChessAstMapper.toStvnAst(original, schemaContent);
    GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);

    assertEquals(original, reconstructed);
    assertEquals(trickyName, reconstructed.whitePlayer());
  }
}
