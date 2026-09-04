package org.stvnadore.chess.codec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.*;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ChessBinaryCodecTest {

  private static ChessBinaryCodec codec;
  private static String rawSchemaText;

  @BeforeAll
  static void setUp() throws Exception {
    try (InputStream is = ChessBinaryCodecTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is, "Schema resource must exist");
      rawSchemaText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      codec = new ChessBinaryCodec(rawSchemaText);
    }
  }

  @Test
  @DisplayName("Round-trip encoding and decoding preserves 100% isomorphic value equivalence")
  void testIsomorphicRoundTrip() {
    Move move1 = new Move(Square.fromAlgebraic("d2"), Square.fromAlgebraic("d4"), Optional.empty(), false, 0);
    TurnState turn1 = new TurnState(1, Piece.PieceColor.WHITE, move1, "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1", 30);

    Move move2 = new Move(Square.fromAlgebraic("d7"), Square.fromAlgebraic("d5"), Optional.empty(), false, 0);
    TurnState turn2 = new TurnState(2, Piece.PieceColor.BLACK, move2, "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2", 28);

    GameHistory original = new GameHistory("game-test-01", "Magnus", "Hikaru", List.of(turn1, turn2), Optional.of(GameHistory.TerminalOutcome.DRAW));

    ByteBuffer buffer = codec.encode(original);
    assertNotNull(buffer);
    assertTrue(buffer.remaining() > 37, "Encoded binary should contain header and payload");

    GameHistory decoded = codec.decode(buffer);
    assertEquals(original, decoded);
  }

  @Test
  @DisplayName("Tampered header byte triggers PoisonedRegistryPayloadException fail-fast")
  void testPoisonedPayloadRejection() {
    Move move = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, move, "fen-1", 25);
    GameHistory game = new GameHistory("game-poison-01", "P1", "P2", List.of(turn), Optional.empty());

    ByteBuffer buffer = codec.encode(game);
    byte[] validBytes = new byte[buffer.remaining()];
    buffer.get(validBytes);

    byte[] poisonedBytes = ChessBinaryCodec.poisonPayload(validBytes);

    assertThrows(PoisonedRegistryPayloadException.class, () -> codec.decode(ByteBuffer.wrap(poisonedBytes)));
  }

  @Test
  @DisplayName("Negative schema constraints reject invalid bounds and illegal states")
  void testNegativeSchemaConstraints() {
    String defsOnly = rawSchemaText.trim();
    if (defsOnly.startsWith("{")) {
      defsOnly = defsOnly.substring(1, defsOnly.lastIndexOf('}'));
    }

    // 1. Halfmoves > 100
    String docHalfmoves101 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 101 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docHalfmoves101));

    // 2. Promotion to #KING (impossible state)
    String docPromoKing = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 7 ) ( #E 8 ) #Some #KING #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docPromoKing));

    // 3. Rank = 9 (out of 1..8 range)
    String docRank9 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 9 ) ( #E 4 ) #None #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docRank9));
  }
}