package org.stvnadore.chess.client;

import io.javalin.Javalin;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.chess.bench.ChessWireBenchmarker;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.repository.SimpleSchemaRepositoryEngine;
import org.stvnadore.repository.edge.SchemaPublishHandler;
import org.stvnadore.repository.infrastructure.ConcurrentHashMapCache;
import org.stvnadore.repository.infrastructure.DatabaseInitializer;
import org.stvnadore.repository.infrastructure.FileSystemCasStorage;
import org.stvnadore.repository.infrastructure.JdbcIndexRepository;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChessSchemaRegistryClient Real-Repository REST & 2/64 CAS Contract Suite")
public class ChessSchemaRegistryClientTest {

  private static final String EXPECTED_REPO_CAS_HASH =
      "b5f53562247e08e093b75e795ea1f8e85d3c80cf297b4958f1fce718f07a5837";
  private static final String EXPECTED_WIRE_CAS_HASH =
      "2e4b82fdf50c9245ecdbfda202af932dd86c8e1596c929f18c4760aa2698999a";

  private static String schemaContent;

  @TempDir
  Path tempCasDir;

  private Javalin app;
  private String baseUrl;

  @BeforeAll
  static void loadCanonicalSchema() throws Exception {
    try (InputStream is = ChessSchemaRegistryClientTest.class.getResourceAsStream("/schemas/chess_turn.gaming.stvn_d")) {
      assertNotNull(is, "Schema resource /schemas/chess_turn.gaming.stvn_d must exist");
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @BeforeEach
  void startRealRepositoryServer() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:chess_client_repo_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    DatabaseInitializer.initialize(dataSource, true);

    FileSystemCasStorage casStorage = new FileSystemCasStorage(tempCasDir);
    JdbcIndexRepository indexRepository = new JdbcIndexRepository(dataSource);
    ConcurrentHashMapCache catalogCache = new ConcurrentHashMapCache();
    SimpleSchemaRepositoryEngine engine = new SimpleSchemaRepositoryEngine(casStorage, indexRepository, catalogCache);
    SchemaPublishHandler handler = new SchemaPublishHandler(engine, casStorage);

    app = Javalin.create();
    handler.configureRoutes(app);
    app.start(0);
    baseUrl = "http://localhost:" + app.port();
  }

  @AfterEach
  void stopRealRepositoryServer() {
    if (app != null) {
      app.stop();
    }
  }

  @Test
  @DisplayName("Client publishes chess_turn.gaming.stvn_d to real repository, materializes 2/64 .stvn_cas.stvn shard, and retrieves canonical schema")
  void testPublishAndFetchChessSchemaAgainstRealRepository() throws Exception {
    ChessSchemaRegistryClient client = new ChessSchemaRegistryClient(baseUrl);

    PublishResultDto publishResult = client.publishSchema("chess_turn.gaming.stvn_d", schemaContent);
    assertEquals("chess_turn.gaming.stvn_d", publishResult.schemaName());
    assertEquals(EXPECTED_REPO_CAS_HASH, publishResult.casHash());
    assertEquals("gaming", publishResult.domain());
    assertEquals(0, publishResult.warningsCount(), "Clean schema must report 0 warnings");

    Path expectedShard = tempCasDir
        .resolve(EXPECTED_REPO_CAS_HASH.substring(0, 2))
        .resolve(EXPECTED_REPO_CAS_HASH + ".stvn_cas.stvn");
    assertTrue(Files.isRegularFile(expectedShard),
        () -> "Real repository must materialize 2/64 CAS shard on disk: " + expectedShard);

    // Publish a distinct tautological schema to verify X-STVN-Warnings-Count header propagation
    String tautologicalVariant = schemaContent
        .replace("#name \"chess_turn\"", "#name \"chess_turn_taut\"")
        .replace(
            ":SquareIndex      { #unsigned #size 6 } :Int",
            ":SquareIndex      { #unsigned #size 6 #maxExcl 64 } :Int"
        );
    PublishResultDto tautResult = client.publishSchema("chess_turn_taut.gaming.stvn_d", tautologicalVariant);
    assertEquals("chess_turn_taut.gaming.stvn_d", tautResult.schemaName());
    assertEquals("gaming", tautResult.domain());
    assertEquals(1, tautResult.warningsCount(), "Tautological schema must report 1 W003 warning from header");

    String fetchedFlattenedText = client.fetchSchemaByCasHash(EXPECTED_REPO_CAS_HASH);
    ChessBinaryCodec hydratedCodec = new ChessBinaryCodec(fetchedFlattenedText);
    assertEquals(EXPECTED_WIRE_CAS_HASH, hydratedCodec.getCasHashHex(),
        "Hydrated codec from repository CAS payload must produce identical Strategy 0x07 wire SHA-256");

    assertThrows(IllegalStateException.class, () ->
        client.fetchSchemaByCasHash("0000000000000000000000000000000000000000000000000000000000000000"));
  }

  @Test
  @DisplayName("Client publishes Strategy 0x08 .stvn_bd binary schema and fetches on-demand binary Strategy 0x08 envelope by CAS hash")
  void testBinarySchemaPublicationAndBinaryCasFetch() throws Exception {
    ChessSchemaRegistryClient client = new ChessSchemaRegistryClient(baseUrl);
    ChessBinaryCodec codec = new ChessBinaryCodec(schemaContent);

    ByteBuffer bdBuffer = codec.encodeSchemaSelfDescribing();
    byte[] binarySchemaBytes = new byte[bdBuffer.remaining()];
    bdBuffer.get(binarySchemaBytes);

    PublishResultDto result = client.publishBinarySchema("chess_turn.gaming.stvn_bd", binarySchemaBytes);
    assertEquals("chess_turn.gaming.stvn_bd", result.schemaName());
    assertEquals(EXPECTED_REPO_CAS_HASH, result.casHash());
    assertEquals("gaming", result.domain());
    assertEquals(0, result.warningsCount());

    byte[] fetchedBin = client.fetchBinarySchemaByCasHash(result.casHash());
    assertTrue(fetchedBin.length > 10);
    assertEquals((byte) 0x88, fetchedBin[4], "Fetched binary CAS schema must use Strategy 0x08 with CRC-32C (0x88)");

    var rootPtr = StvnBinaryDecoder.open(ByteBuffer.wrap(fetchedBin));
    StvnDocumentMeta meta = rootPtr.meta().orElseThrow();
    assertEquals(Optional.of(StvnDocumentKind.DEFS), meta.kind());
    assertEquals(Optional.of("chess_turn"), meta.name());
    assertEquals(Optional.of("gaming"), meta.domain());
  }

  @Test
  @DisplayName("HTTP 422 structured CompileDiagnosticDto payloads pin E028 (Strategy 0x07 rejection at [4, 5)) and E031 (#BODY kind mismatch)")
  void testHttp422StructuredDiagnosticsOnStrategy0x07AndDocumentKindMismatch() {
    ChessSchemaRegistryClient client = new ChessSchemaRegistryClient(baseUrl);
    ChessBinaryCodec codec = new ChessBinaryCodec(schemaContent);

    // 1. Uploading a Strategy 0x07 #BODY game frame to .stvn_bd endpoint -> HTTP 422 E028 / ERR_UNSUPPORTED_STRATEGY at [4, 5)
    ByteBuffer gameBuf = codec.encode(ChessWireBenchmarker.getOperaGame());
    byte[] gameFrameBytes = new byte[gameBuf.remaining()];
    gameBuf.get(gameFrameBytes);

    SchemaRegistryException strategyEx = assertThrows(
        SchemaRegistryException.class,
        () -> client.publishBinarySchema("chess_turn.gaming.stvn_bd", gameFrameBytes)
    );
    assertEquals(422, strategyEx.statusCode());
    assertEquals(1, strategyEx.diagnostics().size());
    CompileDiagnosticDto dStrategy = strategyEx.diagnostics().getFirst();
    assertEquals("E028", dStrategy.canonicalCode());
    assertEquals("ERR_UNSUPPORTED_STRATEGY", dStrategy.symbolicId());
    assertEquals(4, dStrategy.startOffset());
    assertEquals(5, dStrategy.endOffset());

    // 2. Uploading a #BODY text document to .stvn_d endpoint -> HTTP 422 E031 / ERR_DOCUMENT_KIND_MISMATCH
    String bodyText = """
        {
          :meta { #name "chess_turn" #domain "gaming" #kind #BODY }
          :type :Int
          :body 42
        }
        """;
    SchemaRegistryException kindEx = assertThrows(
        SchemaRegistryException.class,
        () -> client.publishSchema("chess_turn.gaming.stvn_d", bodyText)
    );
    assertEquals(422, kindEx.statusCode());
    assertFalse(kindEx.diagnostics().isEmpty());
    CompileDiagnosticDto dKind = kindEx.diagnostics().getFirst();
    assertEquals("E031", dKind.canonicalCode());
    assertEquals("ERR_DOCUMENT_KIND_MISMATCH", dKind.symbolicId());
  }
}
