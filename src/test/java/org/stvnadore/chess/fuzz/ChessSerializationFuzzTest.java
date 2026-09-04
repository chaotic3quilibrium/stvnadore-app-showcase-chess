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
import org.stvnadore.chess.engine.TerminalDetector;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class ChessSerializationFuzzTest {

  private static ChessBinaryCodec codec;
  private static String schemaContent;

  @BeforeAll
  static void setUp() throws Exception {
    try (InputStream is = ChessSerializationFuzzTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is);
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      codec = new ChessBinaryCodec(schemaContent);
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
        // Reset board upon terminal state to accumulate continuous plies
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

    // 1. Direct AST Round-trip Verification
    var ast = ChessAstMapper.toStvnAst(longGame, schemaContent);
    GameHistory astDecoded = ChessAstMapper.fromStvnAst(ast);
    assertEquals(longGame, astDecoded, "AST round-trip must maintain 100% fidelity on 550 turns");

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

    // Flip random byte in header (0..36)
    int headerIndex = rng.nextInt(37);
    byte[] corruptedHeader = validBytes.clone();
    corruptedHeader[headerIndex] ^= (byte) (1 << rng.nextInt(8));

    assertThrows(Exception.class, () -> codec.decode(ByteBuffer.wrap(corruptedHeader)),
        "Corrupted header at index " + headerIndex + " must be rejected");
  }

  @Test
  @DisplayName("Negative schema constraints reject TurnNumber > 1023 (Uint10 overflow)")
  void testTurnNumberOverflowRejection() {
    String defsOnly = schemaContent.trim();
    if (defsOnly.startsWith("{")) {
      defsOnly = defsOnly.substring(1, defsOnly.lastIndexOf('}'));
    }
    String docTurn1024 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g-overflow\" \"W\" \"B\" [ ( 1024 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 0 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docTurn1024));
  }

  @Test
  @DisplayName("Negative schema constraints reject Halfmoves > 100 (Uint7 constraint)")
  void testHalfmovesOverflowRejection() {
    String defsOnly = schemaContent.trim();
    if (defsOnly.startsWith("{")) {
      defsOnly = defsOnly.substring(1, defsOnly.lastIndexOf('}'));
    }
    String docHalfmove101 = "{\n  " + defsOnly + "\n  :type :GameHistory\n  :body (\n" +
        "    \"g-halfmove\" \"W\" \"B\" [ ( 1 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 101 ) \"fen\" 0 ) ] #None\n  )\n}";
    assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(docHalfmove101));
  }

  private static GameHistory buildSmallGame() {
    Move m = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, m, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", 20);
    return new GameHistory("small-game", "W", "B", List.of(turn), Optional.empty());
  }
}
