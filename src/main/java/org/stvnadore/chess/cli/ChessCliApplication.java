package org.stvnadore.chess.cli;

import org.stvnadore.chess.bench.BenchmarkResult;
import org.stvnadore.chess.bench.ChessWireBenchmarker;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.chess.engine.FenCodec;
import org.stvnadore.chess.engine.IllegalMoveException;
import org.stvnadore.chess.engine.MoveValidator;
import org.stvnadore.chess.engine.PgnParseException;
import org.stvnadore.chess.engine.PgnParser;
import org.stvnadore.chess.engine.TerminalDetector;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

/**
 * Command-Line Interface for the STVN Chess AI Reference Application.
 */
public class ChessCliApplication {

  private ChessCliApplication() {
    // Application entry class, non-instantiable
  }

  private static final String SCHEMA_RESOURCE_PATH = "/schemas/chess_turn.stvn_inclf";

  /**
   * Main CLI entry point.
   *
   * @param args command-line arguments: simulate | verify | poison | benchmark | replay | pgn-import
   */
  public static void main(String[] args) {
    int exitCode = execute(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /**
   * Executes CLI commands and returns standard exit status codes.
   *
   * @param args command-line arguments
   * @return 0 on success, 1 on argument/IO error, 2 on PoisonedRegistryPayloadException, 3 on Rule/PGN exception
   */
  public static int execute(String[] args) {
    if (args == null || args.length == 0) {
      printUsage();
      return 1;
    }

    String command = args[0].toLowerCase(java.util.Locale.ROOT);
    try {
      String schemaText = loadEmbeddedSchema();
      ChessBinaryCodec codec = new ChessBinaryCodec(schemaText);

      switch (command) {
        case "simulate" -> {
          runSimulate(codec, args);
          return 0;
        }
        case "verify" -> {
          runVerify(codec, args);
          return 0;
        }
        case "poison" -> {
          runPoison(codec, args);
          return 0;
        }
        case "benchmark" -> {
          runBenchmark(schemaText, args);
          return 0;
        }
        case "replay" -> {
          runReplay(codec, args);
          return 0;
        }
        case "pgn-import" -> {
          runPgnImport(codec, args);
          return 0;
        }
        default -> {
          System.err.println("Unknown command: " + command);
          printUsage();
          return 1;
        }
      }
    } catch (PoisonedRegistryPayloadException e) {
      System.err.println("SECURITY ALERT: Poisoned registry payload detected! " + e.getMessage());
      return 2;
    } catch (IllegalMoveException | PgnParseException e) {
      System.err.println("CHESS ENGINE ERROR: " + e.getMessage());
      return 3;
    } catch (Exception e) {
      System.err.println("Error executing command '" + command + "': " + e.getMessage());
      return 1;
    }
  }

  private static void runSimulate(ChessBinaryCodec codec, String[] args) throws Exception {
    System.out.println("=== STVN Chess AI: Executing Dynamic Game Turn Simulation ===");
    GameHistory sampleGame = buildSimulatedGame();

    System.out.println("Game ID: " + sampleGame.gameId());
    System.out.println("Matchup: " + sampleGame.whitePlayer() + " vs " + sampleGame.blackPlayer());
    System.out.println("Turns count: " + sampleGame.turns().size());
    System.out.println("Final Outcome: " + sampleGame.result().map(Enum::name).orElse("IN_PROGRESS"));

    ByteBuffer binaryBuffer = codec.encode(sampleGame);
    byte[] binaryBytes = new byte[binaryBuffer.remaining()];
    binaryBuffer.get(binaryBytes);

    Path outputPath = Paths.get(args.length > 1 ? args[1] : "game_state.stvn_bin");
    Files.write(outputPath, binaryBytes);
    System.out.println("Binary payload successfully written to: " + outputPath.toAbsolutePath() +
        " (" + binaryBytes.length + " bytes)");

    // Verify round-trip immediately
    GameHistory decoded = codec.decode(ByteBuffer.wrap(binaryBytes));
    if (!decoded.equals(sampleGame)) {
      throw new IllegalStateException("Round-trip decoded game state did not match original game!");
    }
    System.out.println("Round-trip isomorphic verification: SUCCESS (100% fidelity)");
  }

  private static void runVerify(ChessBinaryCodec codec, String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: verify <file.stvn_bin>");
      throw new IllegalArgumentException("Missing target binary file argument");
    }

    Path inputPath = Paths.get(args[1]);
    if (!Files.exists(inputPath)) {
      System.err.println("File not found: " + inputPath.toAbsolutePath());
      throw new IllegalArgumentException("File not found: " + inputPath.toAbsolutePath());
    }

    byte[] bytes = Files.readAllBytes(inputPath);
    System.out.println("Verifying binary payload: " + inputPath.toAbsolutePath() + " (" + bytes.length + " bytes)");

    GameHistory decoded = codec.decode(ByteBuffer.wrap(bytes));
    System.out.println("Schema SHA-256 CAS verification: PASSED");
    System.out.println("Decoded Game ID: " + decoded.gameId());
    System.out.println("Decoded Turns: " + decoded.turns().size());
    System.out.println("Result: " + decoded.result().map(Enum::name).orElse("IN_PROGRESS"));
  }

  private static void runPoison(ChessBinaryCodec codec, String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: poison <file.stvn_bin>");
      throw new IllegalArgumentException("Missing target binary file argument");
    }

    Path inputPath = Paths.get(args[1]);
    if (!Files.exists(inputPath)) {
      System.err.println("File not found: " + inputPath.toAbsolutePath());
      throw new IllegalArgumentException("File not found: " + inputPath.toAbsolutePath());
    }

    byte[] originalBytes = Files.readAllBytes(inputPath);
    byte[] poisonedBytes = ChessBinaryCodec.poisonPayload(originalBytes);

    System.out.println("Simulating zero-trust attack: Header SHA-256 hash corrupted at byte 5.");
    System.out.println("Attempting decode on poisoned payload...");

    try {
      codec.decode(ByteBuffer.wrap(poisonedBytes));
      throw new IllegalStateException("CRITICAL SECURITY FLAW: Poisoned payload was accepted without error!");
    } catch (PoisonedRegistryPayloadException e) {
      System.out.println("SECURITY SUCCESS: PoisonedRegistryPayloadException correctly raised.");
      System.out.println("Exception message: " + e.getMessage());
    }
  }

  private static void runBenchmark(String schemaText, String[] args) throws Exception {
    System.out.println("=== STVN Chess AI: Multi-Format Wire Size Benchmarking Suite ===");
    ChessWireBenchmarker benchmarker = new ChessWireBenchmarker(schemaText);

    List<GameHistory> testGames = List.of(
        ChessWireBenchmarker.getOperaGame(),
        ChessWireBenchmarker.getKasparovDeepBlueGame(),
        ChessWireBenchmarker.generateRandomGame("random-match-100", 100, 42L)
    );

    for (GameHistory game : testGames) {
      BenchmarkResult res = benchmarker.benchmarkGame(game);
      printBenchmarkTable(res);
    }
  }

  private static void runReplay(ChessBinaryCodec codec, String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: replay <file.stvn_bin> [--delay <ms>] [--step] [--ascii]");
      throw new IllegalArgumentException("Missing target binary file argument");
    }

    String filePath = null;
    int delayMs = 500;
    boolean stepMode = false;
    boolean plainAscii = false;

    for (int i = 1; i < args.length; i++) {
      String arg = args[i];
      if ("--step".equalsIgnoreCase(arg)) {
        stepMode = true;
      } else if ("--ascii".equalsIgnoreCase(arg)) {
        plainAscii = true;
      } else if ("--delay".equalsIgnoreCase(arg) && i + 1 < args.length) {
        delayMs = Integer.parseInt(args[++i]);
      } else if (filePath == null && !arg.startsWith("--")) {
        filePath = arg;
      }
    }

    if (filePath == null) {
      throw new IllegalArgumentException("Target .stvn_bin file path not specified");
    }

    Path inputPath = Paths.get(filePath);
    if (!Files.exists(inputPath)) {
      throw new IllegalArgumentException("File not found: " + inputPath.toAbsolutePath());
    }

    byte[] bytes = Files.readAllBytes(inputPath);
    GameHistory game = codec.decode(ByteBuffer.wrap(bytes));

    System.out.println("=== STVN Chess Replay ===");
    System.out.println("Game ID: " + game.gameId());
    System.out.println("Match: " + game.whitePlayer() + " (White) vs " + game.blackPlayer() + " (Black)");
    System.out.println("Total Plies: " + game.turns().size());
    System.out.println("Result: " + game.result().map(Enum::name).orElse("IN_PROGRESS"));
    System.out.println();

    AsciiBoardRenderer.RenderOptions options = plainAscii
        ? AsciiBoardRenderer.RenderOptions.plainAscii()
        : AsciiBoardRenderer.RenderOptions.defaultUnicode();

    TurnState prevTurn = null;
    Scanner scanner = stepMode ? new Scanner(System.in) : null;

    for (int i = 0; i < game.turns().size(); i++) {
      TurnState turn = game.turns().get(i);
      String frame = AsciiBoardRenderer.renderTurn(turn, prevTurn, options);
      System.out.print(frame);

      prevTurn = turn;
      if (stepMode && scanner != null) {
        System.out.print("[Ply " + (i + 1) + "/" + game.turns().size() + " - Enter: Next, q: Quit] > ");
        if (scanner.hasNextLine()) {
          String input = scanner.nextLine().trim();
          if ("q".equalsIgnoreCase(input)) {
            System.out.println("Replay aborted by user.");
            return;
          }
        }
      } else {
        if (delayMs > 0) {
          Thread.sleep(delayMs);
        }
      }
    }
    System.out.println("=== End of Match Replay ===");
  }

  private static void runPgnImport(ChessBinaryCodec codec, String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: pgn-import <input.pgn> [output.stvn_bin]");
      throw new IllegalArgumentException("Missing target PGN file argument");
    }

    Path inputPgnPath = Paths.get(args[1]);
    if (!Files.exists(inputPgnPath)) {
      throw new IllegalArgumentException("PGN file not found: " + inputPgnPath.toAbsolutePath());
    }

    String pgnContent = Files.readString(inputPgnPath, StandardCharsets.UTF_8);
    GameHistory game = PgnParser.parse(pgnContent, inputPgnPath.getFileName().toString());

    System.out.println("=== STVN Chess PGN Import ===");
    System.out.println("Parsed Game ID: " + game.gameId());
    System.out.println("White: " + game.whitePlayer() + " | Black: " + game.blackPlayer());
    System.out.println("Parsed Turns: " + game.turns().size() + " plies");
    System.out.println("Outcome: " + game.result().map(Enum::name).orElse("IN_PROGRESS"));

    ByteBuffer binaryBuffer = codec.encode(game);
    byte[] binaryBytes = new byte[binaryBuffer.remaining()];
    binaryBuffer.get(binaryBytes);

    Path outputPath = (args.length > 2)
        ? Paths.get(args[2])
        : Paths.get(inputPgnPath.toString().replaceAll("(?i)\\.pgn$", "") + ".stvn_bin");

    Files.write(outputPath, binaryBytes);
    System.out.println("Strategy 0x07 binary successfully generated: " + outputPath.toAbsolutePath() +
        " (" + binaryBytes.length + " bytes)");
  }

  private static void printBenchmarkTable(BenchmarkResult res) {
    System.out.println("\n-----------------------------------------------------------------------------------------");
    System.out.printf("Match ID: %-30s | Turns: %d plies\n", res.gameId(), res.turnCount());
    System.out.println("-----------------------------------------------------------------------------------------");
    System.out.printf("%-30s | %10s | %12s | %12s | %10s\n",
        "Wire Format", "Raw Bytes", "Bytes / Ply", "GZIP Bytes", "STVN Delta");
    System.out.println("-----------------------------------------------------------------------------------------");

    int stvnRaw = res.rawSizesBytes().get("STVN Binary (Strategy 0x07)");

    for (String format : res.rawSizesBytes().keySet()) {
      int raw = res.rawSizesBytes().get(format);
      double bpt = res.bytesPerTurn().get(format);
      int gzipped = res.compressedSizesBytes().get(format);
      String deltaStr = format.equals("STVN Binary (Strategy 0x07)")
          ? "BASELINE"
          : String.format("%+.1f%%", ((double) (raw - stvnRaw) / raw) * 100.0);

      System.out.printf("%-30s | %10d | %12.2f | %12d | %10s\n",
          format, raw, bpt, gzipped, deltaStr);
    }
    System.out.println("-----------------------------------------------------------------------------------------");
  }

  /**
   * Generates a fully engine-validated chess match (Scholar's Mate tactical progression).
   *
   * @return GameHistory record with dynamically verified moves and outcomes
   */
  public static GameHistory buildSimulatedGame() {
    String[][] moveCoordinates = {
        {"e2", "e4"},
        {"e7", "e5"},
        {"d1", "h5"},
        {"b8", "c6"},
        {"f1", "c4"},
        {"g8", "f6"},
        {"h5", "f7"}
    };

    int[] evaluations = {25, 20, 50, 45, 90, 80, 10000};

    BoardState currentBoard = BoardState.initial();
    List<TurnState> turns = new ArrayList<>();
    long turnNumber = 1;

    for (int i = 0; i < moveCoordinates.length; i++) {
      Square from = Square.fromAlgebraic(moveCoordinates[i][0]);
      Square to = Square.fromAlgebraic(moveCoordinates[i][1]);
      Piece.PieceColor activeColor = currentBoard.activeColor();

      List<Move> legalMoves = MoveValidator.generateLegalMoves(currentBoard);
      Move executedMove = legalMoves.stream()
          .filter(m -> m.from().equals(from) && m.to().equals(to))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              "Illegal move in simulation: " + from.toAlgebraic() + " -> " + to.toAlgebraic()));

      BoardState nextBoard = MoveValidator.applyMove(currentBoard, executedMove);
      String fen = FenCodec.format(nextBoard);

      turns.add(new TurnState(turnNumber++, activeColor, executedMove, fen, evaluations[i]));
      currentBoard = nextBoard;
    }

    List<Move> terminalLegalMoves = MoveValidator.generateLegalMoves(currentBoard);
    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(currentBoard, terminalLegalMoves);

    return new GameHistory(
        "game-sim-001",
        "Stockfish-Agent",
        "DeepBlue-Agent",
        turns,
        outcome
    );
  }

  private static String loadEmbeddedSchema() throws Exception {
    try (InputStream is = ChessCliApplication.class.getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
      if (is == null) {
        throw new IllegalStateException("Embedded schema resource not found on classpath: " + SCHEMA_RESOURCE_PATH);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void printUsage() {
    System.out.println("""
        STVN Chess AI Reference Application CLI (Java 21 LTS)
        Usage:
          simulate [output_path.stvn_bin]                  Generate turns, write binary, verify round-trip
          verify <file.stvn_bin>                           Decode binary and assert SHA-256 schema match
          poison <file.stvn_bin>                           Corrupt header and assert PoisonedRegistryPayloadException
          benchmark                                        Run multi-format wire size and compression benchmark suite
          replay <file.stvn_bin> [--delay <ms>] [--step] [--ascii]
                                                           Interactive match replay visualizer from STVN binary
          pgn-import <input.pgn> [output.stvn_bin]          Import standard PGN file, validate, and serialize
        """);
  }
}