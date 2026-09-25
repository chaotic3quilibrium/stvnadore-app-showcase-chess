package org.stvnadore.chess.codec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.*;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.readers.StvnTupleReader;
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
    assertTrue(buffer.remaining() > 41, "Encoded binary should contain 37B header, payload, and 4B CRC trailer");

    // Assert Bytes 0..3: ASCII 'STVN' in network byte order and Byte 4 Control Byte 0x87
    assertEquals((byte) 'S', buffer.get(0), "Byte 0 must be 'S'");
    assertEquals((byte) 'T', buffer.get(1), "Byte 1 must be 'T'");
    assertEquals((byte) 'V', buffer.get(2), "Byte 2 must be 'V'");
    assertEquals((byte) 'N', buffer.get(3), "Byte 3 must be 'N'");
    assertEquals((byte) 0x87, buffer.get(4), "Byte 4 must be Control Byte 0x87");

    GameHistory decoded = codec.decode(buffer);
    assertEquals(original, decoded);
  }

  @Test
  @DisplayName("Isomorphic Strategy 0x7 round-trip for canonical Opera Game (1858)")
  void testOperaGameRoundTrip() {
    GameHistory opera = org.stvnadore.chess.bench.ChessWireBenchmarker.getOperaGame();
    ByteBuffer buffer = codec.encode(opera);
    assertNotNull(buffer);
    assertEquals((byte) 0x87, buffer.get(4), "Control byte must evaluate to 0x87");
    GameHistory decoded = codec.decode(buffer);
    assertEquals(opera, decoded);
  }

  @Test
  @DisplayName("Isomorphic Strategy 0x7 round-trip for canonical Immortal Game (1851)")
  void testImmortalGameRoundTrip() {
    GameHistory immortal = org.stvnadore.chess.bench.ChessWireBenchmarker.getImmortalGame();
    ByteBuffer buffer = codec.encode(immortal);
    assertNotNull(buffer);
    assertEquals((byte) 0x87, buffer.get(4), "Control byte must evaluate to 0x87");
    GameHistory decoded = codec.decode(buffer);
    assertEquals(immortal, decoded);
  }

  @Test
  @DisplayName("Isomorphic Strategy 0x7 round-trip for canonical Kasparov vs Deep Blue (1997)")
  void testKasparovDeepBlueRoundTrip() {
    GameHistory kasparov = org.stvnadore.chess.bench.ChessWireBenchmarker.getKasparovDeepBlueGame();
    ByteBuffer buffer = codec.encode(kasparov);
    assertNotNull(buffer);
    assertEquals((byte) 0x87, buffer.get(4), "Control byte must evaluate to 0x87");
    GameHistory decoded = codec.decode(buffer);
    assertEquals(kasparov, decoded);
  }

  @Test
  @DisplayName("Zero-copy flyweight reader inspects GameHistory root tuple without heap deserialization")
  void testZeroCopyRootTupleReading() {
    Move move = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, move, "fen-zero-copy", 15);
    GameHistory original = new GameHistory("game-zc-01", "Magnus", "Hikaru", List.of(turn), Optional.empty());

    ByteBuffer buffer = codec.encode(original);
    StvnTupleReader reader = codec.openRootTuple(buffer);

    assertEquals(5, reader.size(), "GameHistory tuple must contain exactly 5 positional elements");
    assertEquals("game-zc-01", reader.getString(0), "Field 0 must match MatchId");
    assertEquals("Magnus", reader.getString(1), "Field 1 must match WhitePlayer");
    assertEquals("Hikaru", reader.getString(2), "Field 2 must match BlackPlayer");
  }

  @Test
  @DisplayName("Corrupting CRC-32C trailer fails fast with MalformedPayloadException")
  void testCorruptedCrc32cTrailerRejection() {
    Move move = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, move, "fen-1", 25);
    GameHistory game = new GameHistory("game-crc-corrupt", "P1", "P2", List.of(turn), Optional.empty());

    ByteBuffer buffer = codec.encode(game);
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);

    // Mutate the final byte of the 4-byte CRC-32C trailer
    bytes[bytes.length - 1] ^= (byte) 0xFF;

    assertThrows(MalformedPayloadException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
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
    if (defsOnly.contains(":package :org/stvnadore/chess")) {
      int lastBrace = defsOnly.lastIndexOf('}');
      defsOnly = defsOnly.substring(0, lastBrace) + "  :use [ :org/stvnadore/chess { #strip } ]\n  }";
    }

    // 1. Halfmoves > 100
    String docHalfmoves101 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 101 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docHalfmoves101));

    // 2. Promotion to #KING (impossible state under #filterExcl [ #PAWN #KING ])
    String docPromoKing = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 7 ) ( #E 8 ) #Some #KING #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docPromoKing));

    // 3. Promotion to #PAWN (impossible state under #filterExcl [ #PAWN #KING ])
    String docPromoPawn = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 7 ) ( #E 8 ) #Some #PAWN #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docPromoPawn));

    // 4. Rank = 9 (out of 1..8 range)
    String docRank9 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 9 ) ( #E 4 ) #None #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docRank9));
  }
}