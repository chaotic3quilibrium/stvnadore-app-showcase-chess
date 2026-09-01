package org.stvnadore.chess.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable domain record representing a standard chess board coordinate.
 *
 * @param file the board column file (A through H)
 * @param rank the board row rank (1 through 8)
 */
public record Square(File file, int rank) {

  /**
   * Validates coordinate boundaries upon instantiation.
   */
  public Square {
    Objects.requireNonNull(file, "file must not be null");
    if (rank < 1 || rank > 8) {
      throw new IllegalArgumentException("Rank must be between 1 and 8 inclusive: " + rank);
    }
  }

  /**
   * Chess board column files from A to H.
   */
  public enum File {
    /** File A (column 0). */
    A,
    /** File B (column 1). */
    B,
    /** File C (column 2). */
    C,
    /** File D (column 3). */
    D,
    /** File E (column 4). */
    E,
    /** File F (column 5). */
    F,
    /** File G (column 6). */
    G,
    /** File H (column 7). */
    H
  }

  /**
   * Formats this square in standard algebraic notation (e.g. "e4").
   *
   * @return standard algebraic string representation
   */
  public String toAlgebraic() {
    return file.name().toLowerCase(Locale.ROOT) + rank;
  }

  /**
   * Returns 0-based linear square index (0 for A1, 7 for H1, 56 for A8, 63 for H8).
   *
   * @return 0..63 index
   */
  public int toIndex() {
    return (rank - 1) * 8 + file.ordinal();
  }

  /**
   * Instantiates a Square record from a 0-based linear square index.
   *
   * @param index 0..63 coordinate index
   * @return Square instance
   */
  public static Square fromIndex(int index) {
    if (index < 0 || index > 63) {
      throw new IllegalArgumentException("Square index must be between 0 and 63: " + index);
    }
    int fileIdx = index % 8;
    int rank = (index / 8) + 1;
    return new Square(File.values()[fileIdx], rank);
  }

  /**
   * Instantiates a Square record from 0-based file and rank coordinates.
   *
   * @param fileIndex 0..7 (0=A, 7=H)
   * @param rankIndex 0..7 (0=Rank 1, 7=Rank 8)
   * @return Square instance
   */
  public static Square fromCoords(int fileIndex, int rankIndex) {
    if (fileIndex < 0 || fileIndex > 7 || rankIndex < 0 || rankIndex > 7) {
      throw new IllegalArgumentException(
          "Coordinates out of bounds: fileIndex=" + fileIndex + ", rankIndex=" + rankIndex);
    }
    return new Square(File.values()[fileIndex], rankIndex + 1);
  }

  /**
   * Parses standard algebraic notation into a Square record.
   *
   * @param algebraic two-character string (e.g. "e4", "a1")
   * @return valid Square instance
   */
  public static Square fromAlgebraic(String algebraic) {
    Objects.requireNonNull(algebraic, "algebraic square must not be null");
    if (algebraic.length() != 2) {
      throw new IllegalArgumentException("Algebraic notation must be exactly 2 characters: " + algebraic);
    }
    File f = File.valueOf(algebraic.substring(0, 1).toUpperCase(Locale.ROOT));
    int r = Integer.parseInt(algebraic.substring(1, 2));
    return new Square(f, r);
  }
}