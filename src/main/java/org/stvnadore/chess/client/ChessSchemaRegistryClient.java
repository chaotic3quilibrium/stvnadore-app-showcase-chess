package org.stvnadore.chess.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * HTTP Client for interacting with the remote or local STVN Schema Registry.
 */
public class ChessSchemaRegistryClient {

  private final String baseUrl;
  private final HttpClient httpClient;
  private final Duration timeout;

  /**
   * Constructs a new registry client pointing to the specified base URL.
   *
   * @param baseUrl the registry server base URI (e.g. "http://localhost:8080")
   * @param timeout request timeout duration
   */
  public ChessSchemaRegistryClient(String baseUrl, Duration timeout) {
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null").replaceAll("/+$", "");
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build();
  }

  /**
   * Convenience constructor defaulting to 5 seconds timeout.
   *
   * @param baseUrl the registry server base URI
   */
  public ChessSchemaRegistryClient(String baseUrl) {
    this(baseUrl, Duration.ofSeconds(5));
  }

  /**
   * Fetches raw canonical schema text from the repository by its 64-character SHA-256 CAS hash.
   *
   * @param casHash 64-character lowercase hex hash
   * @return raw canonical STVN schema text
   * @throws IOException if network transport fails
   * @throws InterruptedException if thread execution is interrupted
   * @throws IllegalStateException if repository returns non-200 status code
   */
  public String fetchSchemaByCasHash(String casHash) throws IOException, InterruptedException {
    Objects.requireNonNull(casHash, "casHash must not be null");
    if (casHash.length() != 64) {
      throw new IllegalArgumentException("CAS hash must be exactly 64 hex characters: " + casHash);
    }

    URI uri = URI.create(baseUrl + "/api/v1/schemas/cas/" + casHash);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(timeout)
        .header("Accept", "application/stvn")
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Failed to fetch schema for CAS hash " + casHash +
          ". HTTP status: " + response.statusCode() + ", body: " + response.body());
    }

    return response.body();
  }

  /**
   * Publishes a raw `.stvn_inclf` schema definition to the repository.
   *
   * @param schemaName the schema registration name
   * @param sourceText raw schema content
   * @return HTTP status code and response body
   * @throws IOException if network transport fails
   * @throws InterruptedException if thread execution is interrupted
   */
  public HttpResponse<String> publishSchema(String schemaName, String sourceText) throws IOException, InterruptedException {
    Objects.requireNonNull(schemaName, "schemaName must not be null");
    Objects.requireNonNull(sourceText, "sourceText must not be null");

    URI uri = URI.create(baseUrl + "/api/v1/schemas/" + schemaName);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(timeout)
        .header("Content-Type", "application/stvn")
        .POST(HttpRequest.BodyPublishers.ofString(sourceText))
        .build();

    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}