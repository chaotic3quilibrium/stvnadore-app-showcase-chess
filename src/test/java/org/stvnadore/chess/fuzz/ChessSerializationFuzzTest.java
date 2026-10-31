package org.stvnadore.chess.fuzz;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.codec.ChessAstMapper;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.chess.engine.FenCodec;
import org.stvnadore.chess.engine.MoveValidator;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class ChessSerializationFuzzTest {

  private static final String VALID_TURN_1 =
      "( 1 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 0 ) \"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR\" 25 )";
  private static final String VALID_TURN_2 =
      "( 2 #BLACK ( ( #E 7 ) ( #E 5 ) #None #FALSE 0 ) \"rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR\" 20 )";

  private static ChessBinaryCodec codec;
  private static String schemaContent;
  private static String defsOnly;

  @BeforeAll
  static void setUp() throws Exception {
    try (InputStream is = ChessSerializationFuzzTest.class.getResourceAsStream("/schemas/chess_turn.gaming.stvn_d")) {
      assertNotNull(is);
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      codec = new ChessBinaryCodec(schemaContent);
      defsOnly = extractDefsBlockForTest(schemaContent);
    }
  }

  @Test
  @DisplayName("Fuzzing: Continuous randomized legal match simulation across 550+ turns with 100% round-trip equality")
  void testContinuous500TurnLegalMatchSimulation() {
    Random rng = new Random(0xDEADBEEFL);
    List<TurnState> accumulatedTurns = new ArrayList<>(600);
    long turnNumber = 1;

    BoardState currentBoard = BoardState.initial();

    while (accumulatedTurns.size() < 550) {
      List<Move> legalMoves = MoveValidator.generateLegalMoves(currentBoard);
      if (legalMoves.isEmpty() || currentBoard.halfmoveClock() >= 100) {
        currentBoard = BoardState.initial();
        continue;
      }

      Move chosen = legalMoves.get(rng.nextInt(legalMoves.size()));
      BoardState nextBoard = MoveValidator.applyMove(currentBoard, chosen);
      String fen = FenCodec.format(nextBoard);
      int eval = rng.nextInt(2000) - 1000;

      accumulatedTurns.add(new TurnState(turnNumber++, currentBoard.activeColor(), chosen, fen, eval));
      currentBoard = nextBoard;
    }

    assertEquals(550, accumulatedTurns.size());

    GameHistory longGame = new GameHistory(
        "fuzz-match-550-turns",
        "Fuzzer-Alpha",
        "Fuzzer-Beta",
        accumulatedTurns,
        Optional.of(GameHistory.TerminalOutcome.DRAW)
    );

    // 1. Direct Pure-IR Round-trip Verification
    var ast = ChessAstMapper.toStvnAst(longGame, schemaContent);
    GameHistory astDecoded = ChessAstMapper.fromStvnAst(ast);
    assertEquals(longGame, astDecoded, "Pure-IR round-trip must maintain 100% fidelity on 550 turns");

    // 2. Binary Round-trip Verification (Strategy 0x07)
    ByteBuffer buffer = codec.encode(longGame);
    assertNotNull(buffer);
    GameHistory binaryDecoded = codec.decode(buffer);
    assertEquals(longGame, binaryDecoded, "Binary round-trip must maintain 100% fidelity on 550 turns");
  }

  @RepeatedTest(10)
  @DisplayName("Fuzzing: Payload single-bit corruption across binary buffer triggers fail-fast exception")
  void testBitFlipMutationRejection() {
    Random rng = new Random();
    GameHistory game = buildSmallGame();
    ByteBuffer validBuffer = codec.encode(game);
    byte[] validBytes = new byte[validBuffer.remaining()];
    validBuffer.get(validBytes);

    int targetIndex = rng.nextInt(validBytes.length);
    int bitIndex = rng.nextInt(8);
    byte[] corrupted = validBytes.clone();
    corrupted[targetIndex] ^= (byte) (1 << bitIndex);

    Class<? extends Throwable> expectedException = (targetIndex < 4)
        ? IllegalArgumentException.class
        : MalformedPayloadException.class;

    assertThrows(
        expectedException,
        () -> codec.decode(ByteBuffer.wrap(corrupted)),
        "Corrupted byte at index " + targetIndex + " must be rejected with " + expectedException.getSimpleName()
    );
  }

  @Test
  @DisplayName("Sibling Turn Isolation: TurnNumber > 1023 ({ #unsigned #size 10 } :Int) and CentipawnEvaluation > 32767 emit E009 strictly on Turn 3")
  void testTurnNumberAndEvaluationOverflowRejection() {
    assertIsolatedTurn3Fault(
        "( 1024 #WHITE ( ( #G 1 ) ( #F 3 ) #None #FALSE 1 ) \"fen-turn-3\" 30 )",
        "1024",
        "E009",
        "ERR_INTEGER_OVERFLOW"
    );

    assertIsolatedTurn3Fault(
        "( 3 #WHITE ( ( #G 1 ) ( #F 3 ) #None #FALSE 1 ) \"fen-turn-3\" 40000 )",
        "40000",
        "E009",
        "ERR_INTEGER_OVERFLOW"
    );
  }

  @Test
  @DisplayName("Sibling Turn Isolation: Halfmoves > 100 ({ #unsigned #size 7 #maxExcl 101 } :Int) emits E012 strictly on Turn 3")
  void testHalfmovesExceeding100Rejection() {
    assertIsolatedTurn3Fault(
        "( 3 #WHITE ( ( #G 1 ) ( #F 3 ) #None #FALSE 101 ) \"fen-turn-3\" 15 )",
        "101",
        "E012",
        "ERR_CONSTRAINT_VIOLATION"
    );
  }

  @Test
  @DisplayName("Sibling Turn Isolation: Out-of-bounds Rank (0 and 9) emits E012 strictly on Turn 3")
  void testOutOfBoundsCoordinatesRejection() {
    assertIsolatedTurn3Fault(
        "( 3 #WHITE ( ( #G 9 ) ( #F 3 ) #None #FALSE 1 ) \"fen-turn-3\" 15 )",
        "9",
        "E012",
        "ERR_CONSTRAINT_VIOLATION"
    );

    assertIsolatedTurn3Fault(
        "( 3 #WHITE ( ( #G 0 ) ( #F 3 ) #None #FALSE 1 ) \"fen-turn-3\" 15 )",
        "0",
        "E012",
        "ERR_CONSTRAINT_VIOLATION"
    );
  }

  @Test
  @DisplayName("Sibling Turn Isolation: Excluded promotion roles (#KING, #PAWN) emit E012 and unknown color (#SILVER) emits E028 strictly on Turn 3")
  void testInvalidPromotionRoleAndUnknownColorRejection() {
    assertIsolatedTurn3Fault(
        "( 3 #WHITE ( ( #E 7 ) ( #E 8 ) #Some #KING #FALSE 0 ) \"fen-turn-3\" 900 )",
        "#KING",
        "E012",
        "ERR_CONSTRAINT_VIOLATION"
    );

    assertIsolatedTurn3Fault(
        "( 3 #WHITE ( ( #E 7 ) ( #E 8 ) #Some #PAWN #FALSE 0 ) \"fen-turn-3\" 900 )",
        "#PAWN",
        "E012",
        "ERR_CONSTRAINT_VIOLATION"
    );

    assertIsolatedTurn3Fault(
        "( 3 #SILVER ( ( #G 1 ) ( #F 3 ) #None #FALSE 1 ) \"fen-turn-3\" 10 )",
        "#SILVER",
        "E028",
        "ERR_TYPE_MISMATCH"
    );
  }

  @Test
  @DisplayName("Regex Constraint Pinning: Invalid :AlgebraicSquare 'z9' emits E013 / REGEX_MISMATCH on offending literal with sibling isolation")
  void testInvalidAlgebraicSquareRegexRejection() {
    String doc = "{\n  " + defsOnly
        + "\n  :type :Seq( :AlgebraicSquare )\n"
        + "  :body [ \"e2\" \"e4\" \"z9\" ]\n}";

    StvnCompilationResult<StvnDocument> result =
        StvnCompiler.compile(doc, "squares_fault.gaming.stvn", StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertEquals(1, result.diagnostics().size(), () -> "Expected single E013 diagnostic: " + result.diagnostics());

    StvnDiagnostic d = result.diagnostics().getFirst();
    assertEquals(Optional.of("E013"), d.canonicalCode());
    assertEquals(Optional.of("REGEX_MISMATCH"), d.symbolicId());
    int z9Start = doc.indexOf("\"z9\"");
    assertEquals(z9Start, d.startOffset());
    assertEquals(z9Start + 4, d.endOffset());
    assertZeroCompilerJargon(d.message());
  }

  @Test
  @DisplayName("Container Delimiter & Excess Slice Pinning: :Move tuple arity underflow (4 slots) and overflow (7 slots) emit E028 without child/sibling cascade")
  void testMoveTupleArityUnderflowAndOverflowCoordinatePinning() {
    // 1. Underflow: 4-slot :Move (missing HalfmovesSincePawnOrCapture) -> pins strictly to closing ')' of :Move
    String underflowMove = "( ( #G 1 ) ( #F 3 ) #None #FALSE )";
    String underflowTurn3 = "( 3 #WHITE " + underflowMove + " \"fen-turn-3\" 15 )";
    String underflowDoc = buildThreeTurnDocument(underflowTurn3);

    StvnCompilationResult<StvnDocument> underflowRes =
        StvnCompiler.compile(underflowDoc, "move_underflow.gaming.stvn", StvnParserConfig.DEFAULT);
    assertTrue(underflowRes.hasErrors());
    assertEquals(1, underflowRes.diagnostics().size(),
        () -> "Expected exactly 1 diagnostic on 4-slot :Move underflow: " + underflowRes.diagnostics());

    StvnDiagnostic dUnder = underflowRes.diagnostics().getFirst();
    assertEquals(Optional.of("E028"), dUnder.canonicalCode());
    assertEquals(Optional.of("TUPLE_ARITY_MISMATCH"), dUnder.symbolicId());
    int moveStart = underflowDoc.indexOf(underflowMove);
    int moveCloseParen = moveStart + underflowMove.length() - 1;
    assertEquals(moveCloseParen, dUnder.startOffset(), "Underflow must pin strictly to closing ')' delimiter of :Move");
    assertEquals(moveCloseParen + 1, dUnder.endOffset());
    assertZeroCompilerJargon(dUnder.message());

    // 2. Overflow: 7-slot :Move (2 excess elements "999 888") -> pins strictly to excess slice [start("999"), end("888"))
    String overflowMove = "( ( #G 1 ) ( #F 3 ) #None #FALSE 1 999 888 )";
    String overflowTurn3 = "( 3 #WHITE " + overflowMove + " \"fen-turn-3\" 15 )";
    String overflowDoc = buildThreeTurnDocument(overflowTurn3);

    StvnCompilationResult<StvnDocument> overflowRes =
        StvnCompiler.compile(overflowDoc, "move_overflow.gaming.stvn", StvnParserConfig.DEFAULT);
    assertTrue(overflowRes.hasErrors());
    assertEquals(1, overflowRes.diagnostics().size(),
        () -> "Expected exactly 1 diagnostic on 7-slot :Move overflow: " + overflowRes.diagnostics());

    StvnDiagnostic dOver = overflowRes.diagnostics().getFirst();
    assertEquals(Optional.of("E028"), dOver.canonicalCode());
    assertEquals(Optional.of("TUPLE_ARITY_MISMATCH"), dOver.symbolicId());
    int excessStart = overflowDoc.indexOf("999");
    int excessEnd = overflowDoc.indexOf("888") + 3;
    assertEquals(excessStart, dOver.startOffset(), "Overflow must pin strictly to start of first excess element");
    assertEquals(excessEnd, dOver.endOffset(), "Overflow must pin strictly to end of last excess element");
    assertZeroCompilerJargon(dOver.message());
  }

  private static void assertIsolatedTurn3Fault(
      String turn3Text,
      String offendingToken,
      String expectedCanonicalCode,
      String expectedSymbolicId
  ) {
    String doc = buildThreeTurnDocument(turn3Text);
    int turn3Start = doc.indexOf(turn3Text);
    assertTrue(turn3Start > 0);

    StvnCompilationResult<StvnDocument> result =
        StvnCompiler.compile(doc, "match_fault.gaming.stvn", StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation error for Turn 3 fault: " + turn3Text);
    assertEquals(1, result.diagnostics().size(),
        () -> "M == N sibling isolation violated; expected 1 diagnostic on Turn 3, found: " + result.diagnostics());

    StvnDiagnostic diag = result.diagnostics().getFirst();
    assertEquals(Optional.of(expectedCanonicalCode), diag.canonicalCode());
    assertEquals(Optional.of(expectedSymbolicId), diag.symbolicId());

    int tokenOffsetInTurn3 = doc.indexOf(" " + offendingToken + " ", turn3Start);
    assertTrue(tokenOffsetInTurn3 >= turn3Start, "Offending token must be located inside Turn 3");
    int expectedStart = tokenOffsetInTurn3 + 1;
    int expectedEnd = expectedStart + offendingToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertZeroCompilerJargon(diag.message());
  }

  private static String buildThreeTurnDocument(String turn3Text) {
    return "{\n  " + defsOnly
        + "\n  :type :GameHistory\n"
        + "  :body (\n"
        + "    \"match-sibling-isolation\" \"Paul Morphy\" \"Duke Karl\"\n"
        + "    [\n"
        + "      " + VALID_TURN_1 + "\n"
        + "      " + VALID_TURN_2 + "\n"
        + "      " + turn3Text + "\n"
        + "    ]\n"
        + "    #None\n"
        + "  )\n}";
  }

  private static void assertZeroCompilerJargon(String message) {
    String lower = message.toLowerCase(Locale.ROOT);
    assertFalse(lower.contains("token recognition error"), () -> "Leaked ANTLR jargon: " + message);
    assertFalse(lower.contains("lexernoviablealtexception"), () -> "Leaked ANTLR jargon: " + message);
    assertFalse(lower.contains("no viable alternative"), () -> "Leaked ANTLR jargon: " + message);
    assertFalse(lower.contains("mismatched input"), () -> "Leaked ANTLR jargon: " + message);
  }

  private static GameHistory buildSmallGame() {
    Move m = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, m, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", 20);
    return new GameHistory("small-game", "W", "B", List.of(turn), Optional.empty());
  }

  private static String extractDefsBlockForTest(String schema) {
    int defsIdx = schema.indexOf(StvnVocabulary.KEYWORD_DEFS);
    int openBrace = schema.indexOf('{', defsIdx);
    int depth = 0;
    int closeBrace = -1;
    for (int i = openBrace; i < schema.length(); i++) {
      char c = schema.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          closeBrace = i;
          break;
        }
      }
    }
    String defsBlock = schema.substring(defsIdx, closeBrace + 1);
    int lastBrace = defsBlock.lastIndexOf('}');
    return defsBlock.substring(0, lastBrace)
        + "  " + StvnVocabulary.KEYWORD_USE + " [ :gaming/chess { " + StvnVocabulary.FACET_KW_STRIP + " } ]\n  }";
  }
}
