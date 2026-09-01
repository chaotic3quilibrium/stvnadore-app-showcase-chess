package org.stvnadore.chess.bench;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.GameHistory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ChessWireBenchmarkerTest {

  private static ChessWireBenchmarker benchmarker;

  @BeforeAll
  static void setUp() throws Exception {
    try (InputStream is = ChessWireBenchmarkerTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is);
      String schema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      benchmarker = new ChessWireBenchmarker(schema);
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
}
