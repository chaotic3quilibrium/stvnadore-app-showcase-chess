package org.stvnadore.chess.client;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Data Transfer Object representing schema publication responses from the repository.
 *
 * @param schemaName the nominal schema identifier
 * @param shapeSignature canonical structural signature of the schema
 * @param casHash 64-character lowercase hexadecimal SHA-256 content address
 * @param domain optional domain namespace declared in {@code :meta}
 * @param warningsCount count of non-blocking compiler warnings (such as W003) reported by repository
 */
public record PublishResultDto(
    String schemaName,
    String shapeSignature,
    String casHash,
    @Nullable String domain,
    int warningsCount
) {

  /**
   * Convenience constructor defaulting {@code domain} to {@code null} and {@code warningsCount} to {@code 0}.
   *
   * @param schemaName the nominal schema identifier
   * @param shapeSignature canonical structural signature of the schema
   * @param casHash 64-character lowercase hexadecimal SHA-256 content address
   */
  public PublishResultDto(String schemaName, String shapeSignature, String casHash) {
    this(schemaName, shapeSignature, casHash, null, 0);
  }

  /**
   * Convenience constructor defaulting {@code domain} to {@code null}.
   *
   * @param schemaName the nominal schema identifier
   * @param shapeSignature canonical structural signature of the schema
   * @param casHash 64-character lowercase hexadecimal SHA-256 content address
   * @param warningsCount count of non-blocking compiler warnings
   */
  public PublishResultDto(String schemaName, String shapeSignature, String casHash, int warningsCount) {
    this(schemaName, shapeSignature, casHash, null, warningsCount);
  }

  /**
   * Validates DTO non-null field invariants.
   */
  public PublishResultDto {
    Objects.requireNonNull(schemaName, "schemaName must not be null");
    Objects.requireNonNull(shapeSignature, "shapeSignature must not be null");
    Objects.requireNonNull(casHash, "casHash must not be null");
    if (warningsCount < 0) {
      throw new IllegalArgumentException("warningsCount cannot be negative: " + warningsCount);
    }
  }
}
