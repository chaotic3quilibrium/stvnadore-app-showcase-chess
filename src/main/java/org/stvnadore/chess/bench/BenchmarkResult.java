package org.stvnadore.chess.bench;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable metrics report capturing wire size and compression efficiency across formats.
 *
 * @param gameId identifier of the benchmarked game
 * @param turnCount total plies/turns in the game
 * @param rawSizesBytes map of format name to wire byte count
 * @param compressedSizesBytes map of format name to GZIP compressed byte count
 * @param bytesPerTurn map of format name to average bytes per turn
 */
public record BenchmarkResult(
    String gameId,
    int turnCount,
    Map<String, Integer> rawSizesBytes,
    Map<String, Integer> compressedSizesBytes,
    Map<String, Double> bytesPerTurn
) {

  /**
   * Compact constructor enforcing non-null parameters and defensive copies.
   */
  public BenchmarkResult {
    Objects.requireNonNull(gameId, "gameId must not be null");
    rawSizesBytes = Map.copyOf(rawSizesBytes);
    compressedSizesBytes = Map.copyOf(compressedSizesBytes);
    bytesPerTurn = Map.copyOf(bytesPerTurn);
  }

  /**
   * Computes wire size reduction percentage of STVN Binary compared to a baseline format.
   *
   * @param baselineFormat name of target baseline (e.g. "JSON (Compact)")
   * @return percentage reduction (positive means STVN is smaller)
   */
  public double getStvnSavingsPercent(String baselineFormat) {
    int stvnBytes = rawSizesBytes.getOrDefault("STVN Binary (Strategy 0x07)", 0);
    int baselineBytes = rawSizesBytes.getOrDefault(baselineFormat, 0);
    if (baselineBytes == 0) return 0.0;
    return ((double) (baselineBytes - stvnBytes) / baselineBytes) * 100.0;
  }
}