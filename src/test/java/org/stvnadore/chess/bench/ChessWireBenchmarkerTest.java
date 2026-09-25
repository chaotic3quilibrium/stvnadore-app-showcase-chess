package org.stvnadore.chess.bench;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.codec.ChessAstMapper;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.ir.StvnValue;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ChessWireBenchmarkerTest {

  private static ChessWireBenchmarker benchmarker;
  private static ChessBinaryCodec codec;
  private static String schemaText;

  @BeforeAll
  static void setUp() throws Exception {
    try (InputStream is = ChessWireBenchmarkerTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is);
      schemaText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      benchmarker = new ChessWireBenchmarker(schemaText);
      codec = new ChessBinaryCodec(schemaText);
    }
  }

  @Test
  @DisplayName("Opera Game benchmark calculates correct byte sizes and verifies STVN size reduction vs JSON Pretty")
  void testOperaGameBenchmark() {
    GameHistory opera = ChessWireBenchmarker.getOperaGame();
    BenchmarkResult result = benchmarker.benchmarkGame(opera);

    assertNotNull(result);
    assertEquals(33, result.turnCount());

    int stvnSize = result.rawSizesBytes().get("STVN Binary (Strategy 0x07)");
    int jsonPrettySize = result.rawSizesBytes().get("JSON (Pretty)");
    int jsonCompactSize = result.rawSizesBytes().get("JSON (Compact)");
    int flatBinarySize = result.rawSizesBytes().get("Raw Flat Binary");

    assertTrue(stvnSize > 37, "STVN size must include 37B header");
    assertTrue(stvnSize < jsonPrettySize, "STVN Binary must be significantly smaller than Pretty JSON");
    assertTrue(stvnSize < jsonCompactSize, "STVN Binary must be smaller than Compact JSON");
    assertTrue(flatBinarySize < stvnSize, "Flat binary should be smaller than STVN due to zero schema header overhead");

    double savingsVsJsonCompact = result.getStvnSavingsPercent("JSON (Compact)");
    assertTrue(savingsVsJsonCompact > 0.0, "STVN must achieve positive percentage savings against Compact JSON");
  }

  @Test
  @DisplayName("Kasparov vs Deep Blue 1997 Game 6 benchmark execution")
  void testKasparovGameBenchmark() {
    GameHistory kasparov = ChessWireBenchmarker.getKasparovDeepBlueGame();
    BenchmarkResult result = benchmarker.benchmarkGame(kasparov);

    assertEquals(37, result.turnCount());
    assertTrue(result.rawSizesBytes().containsKey("STVN Binary (Strategy 0x07)"));
    assertTrue(result.bytesPerTurn().get("STVN Binary (Strategy 0x07)") > 0.0);
  }

  @Test
  @DisplayName("Flat binary round-trip preserves exact GameHistory equality")
  void testFlatBinaryRoundTrip() {
    GameHistory opera = ChessWireBenchmarker.getOperaGame();
    byte[] encoded = FlatBinaryCodec.encode(opera);
    GameHistory decoded = FlatBinaryCodec.decode(encoded);
    assertEquals(opera, decoded);
  }

  @Test
  @DisplayName("Wire codec throughput benchmark asserts encoding and decoding throughput >= 50,000 turns/second")
  void testWireCodecThroughputBenchmark() {
    GameHistory opera = ChessWireBenchmarker.getOperaGame();
    int turnsPerGame = opera.turns().size();
    ByteBuffer buf = codec.encode(opera);

    // Warmup flyweight decode
    for (int i = 0; i < 2000; i++) {
      var reader = codec.openRootTuple(buf.duplicate());
      assertEquals(5, reader.size());
    }

    // Benchmark Wire Decoding Throughput (Zero-Copy Flyweight Reader)
    int decodeIterations = 5000;
    long startDecode = System.nanoTime();
    for (int i = 0; i < decodeIterations; i++) {
      var reader = codec.openRootTuple(buf.duplicate());
      assertNotNull(reader);
    }
    long elapsedDecodeNanos = System.nanoTime() - startDecode;
    double decodeSeconds = elapsedDecodeNanos / 1_000_000_000.0;
    double decodeTurnsPerSec = (decodeIterations * turnsPerGame) / decodeSeconds;
    System.out.println("Wire Decoding (Flyweight) Throughput: " + decodeTurnsPerSec + " turns/sec");

    // Benchmark Full AST Deserialization Throughput
    long startFullDecode = System.nanoTime();
    for (int i = 0; i < 500; i++) {
      GameHistory decoded = codec.decode(buf.duplicate());
      assertNotNull(decoded);
    }
    long elapsedFullDecodeNanos = System.nanoTime() - startFullDecode;
    double fullDecodeTurnsPerSec = (500 * turnsPerGame) / (elapsedFullDecodeNanos / 1_000_000_000.0);
    System.out.println("Full AST Decoding Throughput: " + fullDecodeTurnsPerSec + " turns/sec");

    // Benchmark Encoding Throughput (AST to Strategy 0x7 Binary Wire)
    StvnValue ast = ChessAstMapper.toStvnAst(opera, schemaText);
    var strategy = new SchemaIdentityStrategy.ExplicitSha256(codec.getExpectedSha256Digest());
    var encoder = new StvnBinaryEncoder(true, strategy, true);

    // Warmup encode
    for (int i = 0; i < 2000; i++) {
      encoder.encode(ast);
    }

    int encodeIterations = 5000;
    long startEncode = System.nanoTime();
    for (int i = 0; i < encodeIterations; i++) {
      ByteBuffer encoded = encoder.encode(ast);
      assertNotNull(encoded);
    }
    long elapsedEncodeNanos = System.nanoTime() - startEncode;
    double encodeSeconds = elapsedEncodeNanos / 1_000_000_000.0;
    double encodeTurnsPerSec = (encodeIterations * turnsPerGame) / encodeSeconds;
    System.out.println("Wire Encoding Throughput: " + encodeTurnsPerSec + " turns/sec");

    assertTrue(decodeTurnsPerSec >= 50_000.0,
        () -> String.format("Decoding throughput %.0f turns/sec must be >= 50,000 turns/sec", decodeTurnsPerSec));
    assertTrue(encodeTurnsPerSec >= 50_000.0,
        () -> String.format("Encoding throughput %.0f turns/sec must be >= 50,000 turns/sec", encodeTurnsPerSec));
    assertTrue(fullDecodeTurnsPerSec >= 10_000.0,
        () -> String.format("Full AST decoding throughput %.0f turns/sec must be >= 10,000 turns/sec", fullDecodeTurnsPerSec));
  }
}
