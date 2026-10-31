package org.stvnadore.chess.integration;

import io.javalin.Javalin;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.chess.bench.ChessWireBenchmarker;
import org.stvnadore.chess.client.ChessSchemaRegistryClient;
import org.stvnadore.chess.client.PublishResultDto;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.readers.StvnTupleReader;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Real-Repository E2E Integration: Publish .stvn_d & .stvn_bd, 2/64 CAS Fetch, and Strategy 0x07 Opera Game Wire Parity")
public class ChessE2EIntegrationTest {

  private static final String EXPECTED_WIRE_SHA256 =
      "2e4b82fdf50c9245ecdbfda202af932dd86c8e1596c929f18c4760aa2698999a";
  private static final String EXPECTED_REPO_CAS_SHA256 =
      "b5f53562247e08e093b75e795ea1f8e85d3c80cf297b4958f1fce718f07a5837";

  private static String schemaContent;

  @TempDir
  Path tempCasDir;

  private Javalin app;
  private String baseUrl;

  @BeforeAll
  static void loadCanonicalSchema() throws Exception {
    try (InputStream is = ChessE2EIntegrationTest.class.getResourceAsStream("/schemas/chess_turn.gaming.stvn_d")) {
      assertNotNull(is, "Schema resource /schemas/chess_turn.gaming.stvn_d must exist");
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @BeforeEach
  void startRealRepositoryServer() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:chess_e2e_repo_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
  @DisplayName("Full E2E Pipeline: Publish chess_turn.gaming.stvn_d to real repository, fetch via 2/64 CAS hash, encode 33-ply Opera Game, and reject poisoned headers")
  void testFullEndToEndPipeline() throws Exception {
    ChessSchemaRegistryClient client = new ChessSchemaRegistryClient(baseUrl);
    ChessBinaryCodec localCodec = new ChessBinaryCodec(schemaContent);
    assertEquals(EXPECTED_WIRE_SHA256, localCodec.getCasHashHex());
    assertEquals(EXPECTED_REPO_CAS_SHA256, localCodec.getRepositoryCasHashHex());

    // 1. Publish .stvn_d Schema to Real Repository Engine
    PublishResultDto publishResult = client.publishSchema("chess_turn.gaming.stvn_d", schemaContent);
    assertNotNull(publishResult);
    assertEquals("chess_turn.gaming.stvn_d", publishResult.schemaName());
    assertEquals("gaming", publishResult.domain());
    assertEquals(localCodec.getRepositoryCasHashHex(), publishResult.casHash());
    assertEquals(0, publishResult.warningsCount());

    Path casShardPath = tempCasDir
        .resolve(publishResult.casHash().substring(0, 2))
        .resolve(publishResult.casHash() + ".stvn_cas.stvn");
    assertTrue(Files.isRegularFile(casShardPath), "2/64 .stvn_cas.stvn shard must exist on disk");

    // 2. Fetch Flattened Canonical Schema by Repository CAS Hash
    String fetchedCanonicalSchema = client.fetchSchemaByCasHash(publishResult.casHash());
    assertNotNull(fetchedCanonicalSchema);

    ChessBinaryCodec remoteCodec = new ChessBinaryCodec(fetchedCanonicalSchema);
    assertEquals(EXPECTED_WIRE_SHA256, remoteCodec.getCasHashHex(),
        "Codec hydrated from repository flattened .stvn_d must compute identical Strategy 0x07 wire SHA-256");

    // 3. Encode and Decode Morphy's 33-Ply 1858 Opera Game Across Local and Remote Codecs
    GameHistory operaGame = ChessWireBenchmarker.getOperaGame();
    assertEquals(33, operaGame.turns().size());

    ByteBuffer localEncoded = localCodec.encode(operaGame);
    ByteBuffer remoteEncoded = remoteCodec.encode(operaGame);

    byte[] localBytes = new byte[localEncoded.remaining()];
    localEncoded.duplicate().get(localBytes);
    byte[] remoteBytes = new byte[remoteEncoded.remaining()];
    remoteEncoded.duplicate().get(remoteBytes);
    assertArrayEquals(localBytes, remoteBytes,
        "Strategy 0x07 binary wire frames must be byte-for-byte identical between local and CAS-hydrated codecs");

    assertEquals(operaGame, remoteCodec.decode(localEncoded.duplicate()));
    assertEquals(operaGame, localCodec.decode(remoteEncoded.duplicate()));

    // 4. Zero-Copy Flyweight Inspection Over Remote Codec
    StvnTupleReader rootReader = remoteCodec.openRootTuple(remoteEncoded.duplicate());
    assertEquals(5, rootReader.size());
    assertEquals(operaGame.gameId(), rootReader.getString(0));
    assertEquals(operaGame.whitePlayer(), rootReader.getString(1));
    assertEquals(operaGame.blackPlayer(), rootReader.getString(2));

    // 5. Corrupt Strategy 0x07 Header SHA-256 Digest and Assert Fail-Closed Rejection
    byte[] poisonedBytes = ChessBinaryCodec.poisonPayload(remoteBytes);
    assertThrows(
        PoisonedRegistryPayloadException.class,
        () -> remoteCodec.decode(ByteBuffer.wrap(poisonedBytes))
    );
    assertThrows(
        PoisonedRegistryPayloadException.class,
        () -> remoteCodec.openRootTuple(ByteBuffer.wrap(poisonedBytes))
    );
  }

  @Test
  @DisplayName("Binary Schema E2E: Encode Strategy 0x08 .stvn_bd, publish to real repository, fetch binary CAS envelope, and round-trip Opera Game")
  void testBinarySchemaPublication() throws Exception {
    ChessSchemaRegistryClient client = new ChessSchemaRegistryClient(baseUrl);
    ChessBinaryCodec localCodec = new ChessBinaryCodec(schemaContent);

    ByteBuffer binBuf = localCodec.encodeSchemaSelfDescribing();
    byte[] binBytes = new byte[binBuf.remaining()];
    binBuf.get(binBytes);
    assertEquals((byte) 0x88, binBytes[4]);

    PublishResultDto result = client.publishBinarySchema("chess_turn.gaming.stvn_bd", binBytes);
    assertNotNull(result);
    assertEquals("chess_turn.gaming.stvn_bd", result.schemaName());
    assertEquals("gaming", result.domain());
    assertEquals(EXPECTED_REPO_CAS_SHA256, result.casHash());
    assertEquals(0, result.warningsCount());

    byte[] fetchedBinSchema = client.fetchBinarySchemaByCasHash(result.casHash());
    var rootPtr = StvnBinaryDecoder.open(ByteBuffer.wrap(fetchedBinSchema));
    String extractedDefsText = StvnBinaryDecoder.extractDefinitionsSource(rootPtr);

    ChessBinaryCodec binHydratedCodec = new ChessBinaryCodec(extractedDefsText);
    assertEquals(EXPECTED_WIRE_SHA256, binHydratedCodec.getCasHashHex());

    GameHistory operaGame = ChessWireBenchmarker.getOperaGame();
    ByteBuffer encodedOpera = binHydratedCodec.encode(operaGame);
    assertEquals(operaGame, localCodec.decode(encodedOpera));
  }
}
