package org.stvnadore.chess.client;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Runtime exception raised when the STVN Schema Registry rejects a request with an HTTP error status.
 */
public class SchemaRegistryException extends IllegalStateException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * HTTP status code returned by the schema registry.
   *
   * @serial
   */
  private final int statusCode;

  /**
   * Immutable list of structured compilation diagnostics returned on HTTP 422 responses.
   *
   * @serial
   */
  private final List<CompileDiagnosticDto> diagnostics;

  /**
   * Constructs a new {@code SchemaRegistryException}.
   *
   * @param message descriptive failure summary
   * @param statusCode HTTP status code returned by the registry
   * @param diagnostics structured compilation diagnostics, or empty list for non-422 errors
   */
  public SchemaRegistryException(
      String message,
      int statusCode,
      List<CompileDiagnosticDto> diagnostics
  ) {
    super(Objects.requireNonNull(message, "message must not be null"));
    this.statusCode = statusCode;
    this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
  }

  /**
   * Returns the HTTP status code returned by the registry.
   *
   * @return HTTP status code
   */
  public int statusCode() {
    return statusCode;
  }

  /**
   * Returns the immutable list of structured diagnostics reported by the registry.
   *
   * @return structured diagnostics list
   */
  public List<CompileDiagnosticDto> diagnostics() {
    return diagnostics;
  }
}
