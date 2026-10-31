package org.stvnadore.chess.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * HTTP Client for interacting with the remote or local STVN Schema Registry.
 */
public class ChessSchemaRegistryClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final TypeReference<List<CompileDiagnosticDto>> DIAGNOSTIC_LIST_TYPE =
      new TypeReference<>() {};

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
   * @throws SchemaRegistryException if repository returns a non-200 status code
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
      throw buildRegistryException(
          "Failed to fetch schema for CAS hash " + casHash,
          response.statusCode(),
          response.body()
      );
    }

    return response.body();
  }

  /**
   * Fetches a Strategy 0x08 ({@code .stvn_bd}) binary schema envelope by its 64-character SHA-256 CAS hash.
   *
   * @param casHash 64-character lowercase hex hash
   * @return raw Strategy 0x08 binary schema bytes
   * @throws IOException if network transport fails
   * @throws InterruptedException if thread execution is interrupted
   * @throws SchemaRegistryException if repository returns a non-200 status code
   */
  public byte[] fetchBinarySchemaByCasHash(String casHash) throws IOException, InterruptedException {
    Objects.requireNonNull(casHash, "casHash must not be null");
    if (casHash.length() != 64) {
      throw new IllegalArgumentException("CAS hash must be exactly 64 hex characters: " + casHash);
    }

    URI uri = URI.create(baseUrl + "/api/v1/schemas/cas/" + casHash);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(timeout)
        .header("Accept", "application/stvn-bin")
        .GET()
        .build();

    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new SchemaRegistryException(
          "Failed to fetch binary schema for CAS hash " + casHash + ". HTTP status: " + response.statusCode(),
          response.statusCode(),
          List.of()
      );
    }
    return response.body();
  }

  /**
   * Publishes a raw {@code .stvn_d} schema definition to the repository.
   *
   * @param schemaName the schema registration name
   * @param sourceText raw schema content
   * @return parsed {@link PublishResultDto} response
   * @throws IOException if network transport fails
   * @throws InterruptedException if thread execution is interrupted
   * @throws SchemaRegistryException if repository rejects the schema
   */
  public PublishResultDto publishSchema(String schemaName, String sourceText) throws IOException, InterruptedException {
    Objects.requireNonNull(schemaName, "schemaName must not be null");
    Objects.requireNonNull(sourceText, "sourceText must not be null");

    URI uri = URI.create(baseUrl + "/api/v1/schemas/" + schemaName);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(timeout)
        .header("Content-Type", "application/stvn")
        .POST(HttpRequest.BodyPublishers.ofString(sourceText))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200 && response.statusCode() != 201) {
      throw buildRegistryException(
          "Failed to publish schema '" + schemaName + "'",
          response.statusCode(),
          response.body()
      );
    }

    int warningsCount = extractWarningsCount(response);
    PublishResultDto parsed = OBJECT_MAPPER.readValue(response.body(), PublishResultDto.class);
    return warningsCount > 0
        ? new PublishResultDto(
            parsed.schemaName(), parsed.shapeSignature(), parsed.casHash(), parsed.domain(), warningsCount)
        : parsed;
  }

  /**
   * Publishes an STVN binary schema envelope utilizing Strategy 0x08 (SelfDescribingSchema)
   * with CRC-32C trailer validation to the repository.
   *
   * @param schemaName the schema registration name
   * @param binaryPayload raw binary payload bytes
   * @return parsed {@link PublishResultDto} response
   * @throws IOException if network transport fails
   * @throws InterruptedException if thread execution is interrupted
   * @throws SchemaRegistryException if repository rejects the binary schema
   */
  public PublishResultDto publishBinarySchema(String schemaName, byte[] binaryPayload)
      throws IOException, InterruptedException {
    Objects.requireNonNull(schemaName, "schemaName must not be null");
    Objects.requireNonNull(binaryPayload, "binaryPayload must not be null");

    URI uri = URI.create(baseUrl + "/api/v1/schemas/" + schemaName);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(timeout)
        .header("Content-Type", "application/stvn-bin")
        .POST(HttpRequest.BodyPublishers.ofByteArray(binaryPayload))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200 && response.statusCode() != 201) {
      throw buildRegistryException(
          "Failed to publish binary schema '" + schemaName + "'",
          response.statusCode(),
          response.body()
      );
    }

    int warningsCount = extractWarningsCount(response);
    PublishResultDto parsed = OBJECT_MAPPER.readValue(response.body(), PublishResultDto.class);
    return warningsCount > 0
        ? new PublishResultDto(
            parsed.schemaName(), parsed.shapeSignature(), parsed.casHash(), parsed.domain(), warningsCount)
        : parsed;
  }

  private static SchemaRegistryException buildRegistryException(
      String prefix,
      int statusCode,
      @Nullable String body
  ) {
    List<CompileDiagnosticDto> diagnostics = List.of();
    if (statusCode == 422 && body != null && body.trim().startsWith("[")) {
      try {
        diagnostics = OBJECT_MAPPER.readValue(body, DIAGNOSTIC_LIST_TYPE);
      } catch (IOException ignored) {
        diagnostics = List.of();
      }
    }
    return new SchemaRegistryException(
        prefix + ". HTTP status: " + statusCode + ", body: " + body,
        statusCode,
        diagnostics
    );
  }

  private static int extractWarningsCount(HttpResponse<?> response) {
    return response.headers()
        .firstValue("X-STVN-Warnings-Count")
        .map(val -> {
          try {
            return Integer.parseInt(val.trim());
          } catch (NumberFormatException e) {
            return 0;
          }
        })
        .orElse(0);
  }
}
