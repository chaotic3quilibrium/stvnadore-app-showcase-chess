package org.stvnadore.chess.client;

import java.util.Objects;

/**
 * Data Transfer Object representing schema publication responses from the repository.
 *
 * @param schemaName the nominal schema identifier
 * @param shapeSignature canonical structural signature of the schema
 * @param casHash 64-character lowercase hexadecimal SHA-256 content address
 */
public record PublishResultDto(
    String schemaName,
    String shapeSignature,
    String casHash
) {

  /**
   * Validates DTO non-null field invariants.
   */
  public PublishResultDto {
    Objects.requireNonNull(schemaName, "schemaName must not be null");
    Objects.requireNonNull(shapeSignature, "shapeSignature must not be null");
    Objects.requireNonNull(casHash, "casHash must not be null");
  }
}