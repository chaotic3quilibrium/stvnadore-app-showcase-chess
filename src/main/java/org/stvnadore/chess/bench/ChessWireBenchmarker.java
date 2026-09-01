package org.stvnadore.chess.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Benchmarking engine comparing .stvn_bin wire sizes against standard JSON and flat layouts.
 */
public class ChessWireBenchmarker {

  private final ChessBinaryCodec codec;
  private final ObjectMapper compactJsonMapper;
  private final ObjectMapper prettyJsonMapper;

  /**
   * Constructs a ChessWireBenchmarker with the given schema definition text.
   *
   * @param schemaContent raw STVN schema content
   */
  public ChessWireBenchmarker(String schemaContent) {
    this.codec = new ChessBinaryCodec(Objects.requireNonNull(schemaContent, "schemaContent must not be null"));
    this.compactJsonMapper = new ObjectMapper().registerModule(new Jdk8Module());
    this.prettyJsonMapper = new ObjectMapper().registerModule(new Jdk8Module()).enable(SerializationFeature.INDENT_OUTPUT);
  }

  /**
   * Benchmarks a single GameHistory record across all supported wire formats.
   *
   * @param game the GameHistory record to evaluate
   * @return complete benchmark metrics report
   */
  public BenchmarkResult benchmarkGame(GameHistory game) {
    Objects.requireNonNull(game, "game must not be null");

    Map<String, Integer> rawSizes = new LinkedHashMap<>();
    Map<String, Integer> compressedSizes = new LinkedHashMap<>();
    Map<String, Double> bytesPerTurn = new LinkedHashMap<>();

    // 1. STVN Binary (Strategy 0x07)
    ByteBuffer stvnBuffer = codec.encode(game);
    byte[] stvnBytes = new byte[stvnBuffer.remaining()];
    stvnBuffer.get(stvnBytes);
    recordFormatMetrics("STVN Binary (Strategy 0x07)", stvnBytes, game.turns().size(), rawSizes, compressedSizes, bytesPerTurn);

    // 2. JSON (Compact)
    try {
      byte[] compactJsonBytes = compactJsonMapper.writeValueAsBytes(game);
      recordFormatMetrics("JSON (Compact)", compactJsonBytes, game.turns().size(), rawSizes, compressedSizes, bytesPerTurn);

      // 3. JSON (Pretty)
      byte[] prettyJsonBytes = prettyJsonMapper.writeValueAsBytes(game);
      recordFormatMetrics("JSON (Pretty)", prettyJsonBytes, game.turns().size(), rawSizes, compressedSizes, bytesPerTurn);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize GameHistory to JSON", e);
    }

    // 4. Flat Binary
    byte[] flatBytes = FlatBinaryCodec.encode(game);
    recordFormatMetrics("Raw Flat Binary", flatBytes, game.turns().size(), rawSizes, compressedSizes, bytesPerTurn);

    return new BenchmarkResult(game.gameId(), game.turns().size(), rawSizes, compressedSizes, bytesPerTurn);
  }

  private void recordFormatMetrics(
      String formatName,
      byte[] rawBytes,
      int turnCount,
      Map<String, Integer> rawSizes,
      Map<String, Integer> compressedSizes,
      Map<String, Double> bytesPerTurn
  ) {
    rawSizes.put(formatName, rawBytes.length);
    byte[] gzipBytes = gzipCompress(rawBytes);
    compressedSizes.put(formatName, gzipBytes.length);
    double bpt = turnCount > 0 ? (double) rawBytes.length / turnCount : 0.0;
    bytesPerTurn.put(formatName, bpt);
  }

  private byte[] gzipCompress(byte[] data) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
      gzos.write(data);
      gzos.finish();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to compress data with GZIP", e);
    }
  }

  /**
   * Generates the canonical 1858 Opera Game (Paul Morphy vs Duke Karl / Count Isouard).
   *
   * @return GameHistory snapshot of the Opera Game
   */
  public static GameHistory getOperaGame() {
    String[][] operaMoves = {
        {"e2", "e4"}, {"e7", "e5"}, {"g1", "f3"}, {"d7", "d6"}, {"d2", "d4"},
        {"c8", "g4"}, {"d4", "e5"}, {"g4", "f3"}, {"d1", "f3"}, {"d6", "e5"},
        {"f1", "c4"}, {"g8", "f6"}, {"f3", "b3"}, {"d8", "e7"}, {"b1", "c3"},
        {"c7", "c6"}, {"c1", "g5"}, {"b7", "b5"}, {"c3", "b5"}, {"c6", "b5"},
        {"c4", "b5"}, {"b8", "d7"}, {"e1", "c1"}, {"a8", "d8"}, {"d1", "d7"},
        {"d8", "d7"}, {"h1", "d1"}, {"e7", "e6"}, {"b5", "d7"}, {"f6", "d7"},
        {"b3", "b8"}, {"d7", "b8"}, {"d1", "d8"}
    };
    return replayMoveArray("opera-game-1858", "Paul Morphy", "Duke Karl & Count Isouard", operaMoves);
  }

  /**
   * Generates Kasparov vs Deep Blue (Game 6, 1997-05-11).
   *
   * @return GameHistory snapshot of Kasparov vs Deep Blue Game 6
   */
  public static GameHistory getKasparovDeepBlueGame() {
    String[][] kasparovMoves = {
        {"e2", "e4"}, {"c7", "c6"}, {"d2", "d4"}, {"d7", "d5"}, {"b1", "c3"},
        {"d5", "e4"}, {"c3", "e4"}, {"b8", "d7"}, {"e4", "g5"}, {"e7", "e6"},
        {"f1", "d3"}, {"g8", "f6"}, {"g1", "f3"}, {"h7", "h6"}, {"g5", "e6"},
        {"d8", "e7"}, {"e1", "g1"}, {"f7", "e6"}, {"d3", "g6"}, {"e8", "d8"},
        {"c1", "f4"}, {"b7", "b5"}, {"a2", "a4"}, {"c8", "b7"}, {"f1", "e1"},
        {"f6", "d5"}, {"f4", "g3"}, {"d8", "c8"}, {"a4", "b5"}, {"c6", "b5"},
        {"d1", "d3"}, {"b7", "c6"}, {"g6", "f5"}, {"e6", "f5"}, {"e1", "e7"},
        {"f8", "e7"}, {"c2", "c4"}
    };
    return replayMoveArray("kasparov-deepblue-1997-g6", "Deep Blue", "Garry Kasparov", kasparovMoves);
  }

  /**
   * Generates a randomized legal match of target plies.
   *
   * @param matchId unique match identifier
   * @param targetTurns maximum number of turns to simulate
   * @param seed random seed for deterministic generation
   * @return simulated GameHistory record
   */
  public static GameHistory generateRandomGame(String matchId, int targetTurns, long seed) {
    Random rng = new Random(seed);
    BoardState currentBoard = BoardState.initial();
    List<TurnState> turns = new ArrayList<>(targetTurns);
    long turnNum = 1;

    for (int i = 0; i < targetTurns; i++) {
      List<Move> legalMoves = MoveValidator.generateLegalMoves(currentBoard);
      if (legalMoves.isEmpty()) {
        break; // Terminal state reached
      }
      Move chosen = legalMoves.get(rng.nextInt(legalMoves.size()));
      BoardState nextBoard = MoveValidator.applyMove(currentBoard, chosen);
      String fen = FenCodec.format(nextBoard);
      int eval = (rng.nextInt(200) - 100);

      turns.add(new TurnState(turnNum++, currentBoard.activeColor(), chosen, fen, eval));
      currentBoard = nextBoard;
    }

    List<Move> terminalLegalMoves = MoveValidator.generateLegalMoves(currentBoard);
    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(currentBoard, terminalLegalMoves);

    return new GameHistory(matchId, "AI-Agent-Alpha", "AI-Agent-Beta", turns, outcome);
  }

  private static GameHistory replayMoveArray(String gameId, String white, String black, String[][] movePairs) {
    BoardState current = BoardState.initial();
    List<TurnState> turns = new ArrayList<>(movePairs.length);
    long turnNum = 1;

    for (String[] pair : movePairs) {
      Square from = Square.fromAlgebraic(pair[0]);
      Square to = Square.fromAlgebraic(pair[1]);

      List<Move> legal = MoveValidator.generateLegalMoves(current);
      Move targetMove = legal.stream()
          .filter(m -> m.from().equals(from) && m.to().equals(to))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Illegal move in replay array: " + from.toAlgebraic() + " -> " + to.toAlgebraic()));

      BoardState next = MoveValidator.applyMove(current, targetMove);
      String fen = FenCodec.format(next);
      turns.add(new TurnState(turnNum++, current.activeColor(), targetMove, fen, 0));
      current = next;
    }

    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(current, MoveValidator.generateLegalMoves(current));

    return new GameHistory(gameId, white, black, turns, outcome);
  }
}