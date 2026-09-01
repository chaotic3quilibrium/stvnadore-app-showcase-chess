package org.stvnadore.chess.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.chess.engine.FenCodec;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class AsciiBoardRendererTest {

  @Test
  @DisplayName("Default Unicode board rendering includes grid coordinates and chess glyphs")
  void testInitialBoardUnicodeRendering() {
    BoardState initial = BoardState.initial();
    String rendered = AsciiBoardRenderer.render(initial);

    assertNotNull(rendered);
    assertTrue(rendered.contains("a   b   c   d   e   f   g   h"));
    assertTrue(rendered.contains("8 | ♜ | ♞ | ♝ | ♛ | ♚ | ♝ | ♞ | ♜ | 8"));
    assertTrue(rendered.contains("7 | ♟ | ♟ | ♟ | ♟ | ♟ | ♟ | ♟ | ♟ | 7"));
    assertTrue(rendered.contains("2 | ♙ | ♙ | ♙ | ♙ | ♙ | ♙ | ♙ | ♙ | 2"));
    assertTrue(rendered.contains("1 | ♖ | ♘ | ♗ | ♕ | ♔ | ♗ | ♘ | ♖ | 1"));
    assertTrue(rendered.contains("Turn: 1 (WHITE)"));
    assertTrue(rendered.contains("Status: ACTIVE (Safe)"));
  }

  @Test
  @DisplayName("Plain ASCII board rendering uses standard alphanumeric piece characters")
  void testInitialBoardPlainAsciiRendering() {
    BoardState initial = BoardState.initial();
    String rendered = AsciiBoardRenderer.render(initial, AsciiBoardRenderer.RenderOptions.plainAscii());

    assertNotNull(rendered);
    assertTrue(rendered.contains("8 | r | n | b | q | k | b | n | r | 8"));
    assertTrue(rendered.contains("7 | p | p | p | p | p | p | p | p | 7"));
    assertTrue(rendered.contains("2 | P | P | P | P | P | P | P | P | 2"));
    assertTrue(rendered.contains("1 | R | N | B | Q | K | B | N | R | 1"));
  }

  @Test
  @DisplayName("Turn metadata panel correctly formats move transition, evaluation, and check status")
  void testTurnMetadataPanelFormatting() {
    Move move = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    String fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1";
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, move, fen, 35);

    String rendered = AsciiBoardRenderer.renderTurn(turn, null, AsciiBoardRenderer.RenderOptions.defaultUnicode());

    assertTrue(rendered.contains("Turn: 1 (BLACK)"));
    assertTrue(rendered.contains("Last Move: e2 -> e4"));
    assertTrue(rendered.contains("Eval: +0.35 CP"));
    assertTrue(rendered.contains("Status: ACTIVE (Safe)"));
    assertTrue(rendered.contains("Halfmove: 0/100"));
  }

  @Test
  @DisplayName("Material balance computation detects piece captures and score delta")
  void testMaterialImbalanceComputation() {
    // Initial board has equal material
    BoardState initial = BoardState.initial();
    AsciiBoardRenderer.MaterialBalance balInitial = AsciiBoardRenderer.computeMaterialBalance(initial);
    assertEquals(0, balInitial.scoreDelta());
    assertTrue(balInitial.whiteCaptured().isEmpty());
    assertTrue(balInitial.blackCaptured().isEmpty());

    // Position where White captured Black's Queen and Black captured a White Pawn
    // White: 8P, 2N, 2B, 2R, 1Q, 1K (Wait, -1P = 7P) -> Material: 7*1 + 3+3+3+3 + 5+5 + 9 = 38
    // Black: 8P, 2N, 2B, 2R, 0Q, 1K -> Material: 8*1 + 3+3+3+3 + 5+5 + 0 = 30
    // Delta = 38 - 30 = +8
    String testFen = "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPP1/RNBQKBNR w KQkq - 0 1";
    BoardState board = FenCodec.parse(testFen);
    AsciiBoardRenderer.MaterialBalance bal = AsciiBoardRenderer.computeMaterialBalance(board);

    assertEquals(8, bal.scoreDelta());
    assertEquals(1, bal.whiteCaptured().size());
    assertEquals(Piece.PieceRole.QUEEN, bal.whiteCaptured().get(0));
    assertEquals(1, bal.blackCaptured().size());
    assertEquals(Piece.PieceRole.PAWN, bal.blackCaptured().get(0));
  }
}
