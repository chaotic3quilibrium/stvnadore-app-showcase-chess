package org.stvnadore.chess.fuzz;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.codec.ChessAstMapper;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.chess.engine.FenCodec;
import org.stvnadore.chess.engine.MoveValidator;
import org.stvnadore.chess.engine.TerminalDetector;
import org.stvnadore.core.ir.StvnValue;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hardening test suite simulating 10 consecutive full random matches to terminal completion.
 * Verifies 100% round-trip AST isomorphism and Strategy 0x07 binary codec fidelity across all games.
 */
public class MultiGameSimulationHardeningTest {

  private static ChessBinaryCodec codec;
  private static String schemaContent;

  @BeforeAll
  static void setUp() throws Exception {
    try (InputStream is = MultiGameSimulationHardeningTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is, "Schema resource /schemas/chess_turn.stvn_inclf must exist");
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      codec = new ChessBinaryCodec(schemaContent);
    }
  }

  @Test
  @DisplayName("Simulate 10 consecutive full random matches to terminal completion with 100% AST and Binary fidelity")
  void testTenConsecutiveFullRandomMatchesToCompletion() {
    int totalGamesToSimulate = 10;
    int checkmateCount = 0;
    int stalemateCount = 0;
    int fiftyMoveCount = 0;
    int insufficientMaterialCount = 0;
    int totalPliesSimulated = 0;

    for (int gameIdx = 1; gameIdx <= totalGamesToSimulate; gameIdx++) {
      long seed = 1000L + gameIdx * 37L;
      Random rng = new Random(seed);

      BoardState currentBoard = BoardState.initial();
      List<TurnState> turns = new ArrayList<>();
      long turnNumber = 1;
      Optional<GameHistory.TerminalOutcome> outcome = Optional.empty();

      while (outcome.isEmpty()) {
        List<Move> legalMoves = MoveValidator.generateLegalMoves(currentBoard);
        outcome = TerminalDetector.detectOutcome(currentBoard, legalMoves);
        if (outcome.isPresent()) {
          break;
        }

        assertFalse(legalMoves.isEmpty(), "Legal moves must not be empty if no terminal outcome was detected");

        Move chosenMove = legalMoves.get(rng.nextInt(legalMoves.size()));
        BoardState nextBoard = MoveValidator.applyMove(currentBoard, chosenMove);
        String fen = FenCodec.format(nextBoard);
        int eval = rng.nextInt(2000) - 1000;

        turns.add(new TurnState(turnNumber++, currentBoard.activeColor(), chosenMove, fen, eval));
        currentBoard = nextBoard;

        // Safety bound against runaway tests (schema Uint10 limit is 1023)
        assertTrue(turnNumber <= 1024, "Turn number must not exceed Uint10 capacity (1023)");
      }

      assertTrue(outcome.isPresent(), "Game " + gameIdx + " must terminate with a valid outcome");
      assertFalse(turns.isEmpty(), "Game " + gameIdx + " must contain at least 1 turn");

      // Categorize terminal outcome
      List<Move> finalLegalMoves = MoveValidator.generateLegalMoves(currentBoard);
      if (finalLegalMoves.isEmpty()) {
        if (MoveValidator.isInCheck(currentBoard, currentBoard.activeColor())) {
          checkmateCount++;
        } else {
          stalemateCount++;
        }
      } else if (currentBoard.halfmoveClock() >= 100) {
        fiftyMoveCount++;
      } else if (TerminalDetector.isInsufficientMaterial(currentBoard)) {
        insufficientMaterialCount++;
      }

      totalPliesSimulated += turns.size();

      String matchId = "hardening-match-" + String.format("%03d", gameIdx);
      String whitePlayer = "SimBot-White-" + gameIdx;
      String blackPlayer = "SimBot-Black-" + gameIdx;
      GameHistory game = new GameHistory(matchId, whitePlayer, blackPlayer, turns, outcome);

      // 1. Verify 100% Round-Trip AST Isomorphism
      StvnValue ast = ChessAstMapper.toStvnAst(game, schemaContent);
      assertNotNull(ast, "AST generation must succeed for match " + matchId);
      GameHistory astDecoded = ChessAstMapper.fromStvnAst(ast);
      assertEquals(game, astDecoded, "AST round-trip must preserve exact equality for match " + matchId);

      // 2. Verify 100% Strategy 0x07 Binary Codec Fidelity
      ByteBuffer encodedBuffer = codec.encode(game);
      assertNotNull(encodedBuffer, "Binary encoding must succeed for match " + matchId);
      assertTrue(encodedBuffer.remaining() > 37, "Binary payload must exceed 37-byte header");

      GameHistory binaryDecoded = codec.decode(encodedBuffer);
      assertEquals(game, binaryDecoded, "Binary round-trip must preserve exact equality for match " + matchId);
      assertEquals(game.turns().size(), binaryDecoded.turns().size());
      assertEquals(game.result(), binaryDecoded.result());
    }

    System.out.printf(
        "[MultiGameSimulationHardeningTest] Successfully completed %d full games (%d total plies). Outcomes: Checkmate=%d, Stalemate=%d, 50-Move=%d, InsufficientMaterial=%d%n",
        totalGamesToSimulate, totalPliesSimulated, checkmateCount, stalemateCount, fiftyMoveCount, insufficientMaterialCount
    );

    assertTrue(totalPliesSimulated > 200, "Simulation of 10 games should produce substantial ply volume");
  }

  @Test
  @DisplayName("Verify simulated match properties satisfy all schema bounds and invariant rules")
  void testSimulatedGameCharacteristics() {
    GameHistory game = simulateSingleGame(42L);
    assertNotNull(game);
    assertTrue(game.turns().size() > 0);
    assertTrue(game.result().isPresent());

    for (TurnState turn : game.turns()) {
      assertTrue(turn.turnNumber() >= 1 && turn.turnNumber() <= 1023, "TurnNumber must fit Uint10");
      assertTrue(turn.move().halfmovesSincePawnOrCapture() <= 100, "Halfmoves must fit Uint7 (<= 100)");
      assertTrue(turn.evaluationCentipawns() >= -32768 && turn.evaluationCentipawns() <= 32767, "Evaluation must fit Int16");
      assertNotNull(turn.fen(), "FEN snapshot must not be null");
      assertFalse(turn.fen().isBlank(), "FEN snapshot must not be blank");
    }
  }

  private static GameHistory simulateSingleGame(long seed) {
    Random rng = new Random(seed);
    BoardState board = BoardState.initial();
    List<TurnState> turns = new ArrayList<>();
    long turnNumber = 1;
    Optional<GameHistory.TerminalOutcome> outcome = Optional.empty();

    while (outcome.isEmpty()) {
      List<Move> legalMoves = MoveValidator.generateLegalMoves(board);
      outcome = TerminalDetector.detectOutcome(board, legalMoves);
      if (outcome.isPresent()) {
        break;
      }
      Move chosen = legalMoves.get(rng.nextInt(legalMoves.size()));
      BoardState nextBoard = MoveValidator.applyMove(board, chosen);
      String fen = FenCodec.format(nextBoard);
      int eval = rng.nextInt(200) - 100;
      turns.add(new TurnState(turnNumber++, board.activeColor(), chosen, fen, eval));
      board = nextBoard;
    }

    return new GameHistory("single-sim-game", "WhiteBot", "BlackBot", turns, outcome);
  }
}
