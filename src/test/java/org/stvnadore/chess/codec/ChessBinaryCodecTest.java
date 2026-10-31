package org.stvnadore.chess.codec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.readers.StvnTupleReader;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
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
    try (InputStream is = ChessBinaryCodecTest.class.getResourceAsStream("/schemas/chess_turn.gaming.stvn_d")) {
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
    assertTrue(buffer.remaining() > 42, "Encoded binary should contain 38B header, payload, and 4B CRC trailer");

    // Assert Bytes 0..3: ASCII 'STVN' in network byte order and Byte 4 Control Byte 0x87
    assertEquals((byte) 'S', buffer.get(0), "Byte 0 must be 'S'");
    assertEquals((byte) 'T', buffer.get(1), "Byte 1 must be 'T'");
    assertEquals((byte) 'V', buffer.get(2), "Byte 2 must be 'V'");
    assertEquals((byte) 'N', buffer.get(3), "Byte 3 must be 'N'");
    assertEquals((byte) 0x87, buffer.get(4), "Byte 4 must be Control Byte 0x87");
    assertEquals((byte) 0x00, buffer.get(5), "Byte 5 must be MetaControlByte 0x00 (#BODY)");

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

    bytes[bytes.length - 1] ^= (byte) 0xFF;

    assertThrows(MalformedPayloadException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
    assertThrows(MalformedPayloadException.class, () -> codec.openRootTuple(ByteBuffer.wrap(bytes)));
  }

  @Test
  @DisplayName("Tampered header byte triggers PoisonedRegistryPayloadException in both decode and openRootTuple")
  void testPoisonedPayloadRejection() {
    Move move = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, move, "fen-1", 25);
    GameHistory game = new GameHistory("game-poison-01", "P1", "P2", List.of(turn), Optional.empty());

    ByteBuffer buffer = codec.encode(game);
    byte[] validBytes = new byte[buffer.remaining()];
    buffer.get(validBytes);

    byte[] poisonedBytes = ChessBinaryCodec.poisonPayload(validBytes);

    assertThrows(
        PoisonedRegistryPayloadException.class,
        () -> codec.decode(ByteBuffer.wrap(poisonedBytes))
    );
    assertThrows(
        PoisonedRegistryPayloadException.class,
        () -> codec.openRootTuple(ByteBuffer.wrap(poisonedBytes)),
        "Zero-copy openRootTuple must also fail closed on poisoned Strategy 0x07 header digest"
    );
  }

  @Test
  @DisplayName("Strategy 0x08 encodeSchemaSelfDescribing emits authentic #DEFS .stvn_bd envelope")
  void testSelfDescribingBinarySchemaEnvelope() {
    ByteBuffer bdBuffer = codec.encodeSchemaSelfDescribing();

    assertEquals((byte) 0x88, bdBuffer.get(4), "Strategy 0x08 with CRC-32C must set Byte 4 to 0x88");
    var rootPtr = StvnBinaryDecoder.open(bdBuffer);
    StvnDocumentMeta meta = rootPtr.meta().orElseThrow();
    assertEquals(Optional.of(StvnDocumentKind.DEFS), meta.kind());
    assertEquals(Optional.of("chess_turn"), meta.name());
    assertEquals(Optional.of("gaming"), meta.domain());
    assertEquals(rawSchemaText, StvnBinaryDecoder.extractDefinitionsSource(rootPtr));
  }

  @Test
  @DisplayName("Invalid chess schema definitions pin exact STVN 2.0.0 dual codes (E008, E011, W003) and [startOffset, endOffset) spans")
  void testInvalidChessSchemaDualCodesAndCoordinates() {
    // 1. Prohibited #maxIncl on discrete :Int -> E011 / ERR_DISCRETE_BOUND_KIND_PROHIBITED
    String badDiscreteBound = """
        {
          :meta { #name "chess_bad_bound" #domain "gaming" #kind #DEFS }
          :defs {
            :package :gaming/chess {
              :File :Enum [ #A #B #C #D #E #F #G #H ]
              :HalfmovesSincePawnOrCapture { #unsigned #size 7 #maxIncl 100 } :Int
            }
          }
        }
        """;
    StvnCompilationResult<StvnDocument> r1 =
        StvnCompiler.compile(badDiscreteBound, "chess_bad_bound.gaming.stvn_d", StvnParserConfig.DEFAULT);
    assertTrue(r1.hasErrors());
    StvnDiagnostic d1 = r1.diagnostics().getFirst();
    assertEquals(Optional.of("E011"), d1.canonicalCode());
    assertEquals(Optional.of("ERR_DISCRETE_BOUND_KIND_PROHIBITED"), d1.symbolicId());
    int maxInclStart = badDiscreteBound.indexOf("#maxIncl");
    assertTrue(d1.startOffset() >= maxInclStart && d1.endOffset() <= badDiscreteBound.indexOf("} :Int"));

    // 2. Facet order violation (#minIncl before #unsigned #size) -> E008 / ERR_FACET_ORDER_VIOLATION
    String badFacetOrder = """
        {
          :meta { #name "chess_bad_order" #domain "gaming" #kind #DEFS }
          :defs {
            :package :gaming/chess {
              :Rank { #minIncl 1 #unsigned #size 4 } :Int
            }
          }
        }
        """;
    StvnCompilationResult<StvnDocument> r2 =
        StvnCompiler.compile(badFacetOrder, "chess_bad_order.gaming.stvn_d", StvnParserConfig.DEFAULT);
    assertTrue(r2.hasErrors());
    StvnDiagnostic d2 = r2.diagnostics().getFirst();
    assertEquals(Optional.of("E008"), d2.canonicalCode());
    assertEquals(Optional.of("ERR_FACET_ORDER_VIOLATION"), d2.symbolicId());
    int unsignedStart = badFacetOrder.indexOf("#unsigned");
    assertEquals(unsignedStart, d2.startOffset());

    // 3. Tautological #minIncl 0 on unsigned :SquareIndex -> W003 / WARN_REDUNDANT_METADATA_FACET
    String tautologicalSquareIndex = """
        {
          :meta { #name "chess_w003" #domain "gaming" #kind #DEFS }
          :defs {
            :package :gaming/chess {
              :SquareIndex { #unsigned #size 6 #minIncl 0 } :Int
            }
          }
        }
        """;
    StvnCompilationResult<StvnDocument> r3 =
        StvnCompiler.compile(tautologicalSquareIndex, "chess_w003.gaming.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(r3.hasErrors());
    StvnDiagnostic w003 = r3.diagnostics().stream()
        .filter(d -> d.canonicalCode().equals(Optional.of("W003")))
        .findFirst()
        .orElseThrow();
    assertEquals(Optional.of("WARN_REDUNDANT_METADATA_FACET"), w003.symbolicId());
    assertTrue(w003.startOffset() >= tautologicalSquareIndex.indexOf("#minIncl 0"));
  }

  @Test
  @DisplayName("Negative schema constraints reject invalid bounds and illegal states with exact E012 dual codes")
  void testNegativeSchemaConstraints() {
    String defsOnly = extractDefsBlockForTest(rawSchemaText);

    // 1. Halfmoves > 100
    String docHalfmoves101 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 101 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertSingleConstraintDiagnostic(docHalfmoves101, "101");

    // 2. Promotion to #KING (impossible state under #filterExcl [ #PAWN #KING ])
    String docPromoKing = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 7 ) ( #E 8 ) #Some #KING #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertSingleConstraintDiagnostic(docPromoKing, "#KING");

    // 3. Promotion to #PAWN (impossible state under #filterExcl [ #PAWN #KING ])
    String docPromoPawn = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 7 ) ( #E 8 ) #Some #PAWN #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertSingleConstraintDiagnostic(docPromoPawn, "#PAWN");

    // 4. Rank = 9 (out of 1..8 range)
    String docRank9 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 9 ) ( #E 4 ) #None #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertSingleConstraintDiagnostic(docRank9, "9");
  }

  @Test
  @DisplayName("Diagnostic Coordinate Pinning: Out-of-bounds coordinate pins exact token without delimiter cascades")
  void testCoordinateDiagnosticPinning() {
    String defsOnly = extractDefsBlockForTest(rawSchemaText);
    String docRank9 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g1\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 9 ) ( #E 4 ) #None #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";

    var ex = assertThrows(MalformedPayloadException.class,
        () -> StvnCompiler.compilePayload(docRank9, null, StvnParserConfig.STRICT));

    int rankOffset = docRank9.indexOf(" 9 )");
    assertTrue(rankOffset > 0, "Offending token 9 must exist in document");
    assertEquals(rankOffset + 1, ex.startOffset(), "Error startOffset must pin exactly to token '9'");
    assertEquals(rankOffset + 2, ex.endOffset(), "Error endOffset must cover token length 1");
  }

  private static void assertSingleConstraintDiagnostic(String doc, String offendingToken) {
    StvnCompilationResult<StvnDocument> result =
        StvnCompiler.compile(doc, "negative_test.gaming.stvn", StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertEquals(1, result.diagnostics().size(), () -> "Expected single diagnostic without cascade: " + result.diagnostics());
    StvnDiagnostic d = result.diagnostics().getFirst();
    assertEquals(Optional.of("E012"), d.canonicalCode());
    assertEquals(Optional.of("ERR_CONSTRAINT_VIOLATION"), d.symbolicId());
    int bodyIdx = doc.indexOf(":body");
    int tokIdx = doc.indexOf(" " + offendingToken + " ", bodyIdx);
    assertTrue(tokIdx > 0);
    assertEquals(tokIdx + 1, d.startOffset());
    assertEquals(tokIdx + 1 + offendingToken.length(), d.endOffset());
  }

  private static String extractDefsBlockForTest(String schemaContent) {
    int defsIdx = schemaContent.indexOf(StvnVocabulary.KEYWORD_DEFS);
    int openBrace = schemaContent.indexOf('{', defsIdx);
    int depth = 0;
    int closeBrace = -1;
    for (int i = openBrace; i < schemaContent.length(); i++) {
      char c = schemaContent.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          closeBrace = i;
          break;
        }
      }
    }
    String defsBlock = schemaContent.substring(defsIdx, closeBrace + 1);
    int lastBrace = defsBlock.lastIndexOf('}');
    return defsBlock.substring(0, lastBrace)
        + "  " + StvnVocabulary.KEYWORD_USE + " [ :gaming/chess { " + StvnVocabulary.FACET_KW_STRIP + " } ]\n  }";
  }
}
