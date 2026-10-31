package org.stvnadore.chess.client;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Structured diagnostic DTO returned by {@code stvnadore-repository} on HTTP 422 schema rejections.
 *
 * @param message human-readable diagnostic summary without compiler jargon
 * @param line 1-based line number
 * @param column 0-based column index
 * @param startOffset 0-based inclusive start character or byte offset
 * @param endOffset 0-based exclusive end character or byte offset
 * @param canonicalCode canonical STVN 2.0.0 diagnostic code (e.g. {@code E001}..{@code E040})
 * @param symbolicId symbolic STVN 2.0.0 diagnostic identifier (e.g. {@code ERR_DOCUMENT_KIND_MISMATCH})
 */
public record CompileDiagnosticDto(
    String message,
    int line,
    int column,
    int startOffset,
    int endOffset,
    @Nullable String canonicalCode,
    @Nullable String symbolicId
) implements Serializable {

  /**
   * Validates non-null message and non-negative coordinate invariants.
   */
  public CompileDiagnosticDto {
    Objects.requireNonNull(message, "message must not be null");
    if (startOffset < 0 || endOffset < startOffset) {
      throw new IllegalArgumentException(
          "Invalid diagnostic span [" + startOffset + ", " + endOffset + ")");
    }
  }
}
