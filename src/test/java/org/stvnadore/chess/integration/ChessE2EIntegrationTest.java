package org.stvnadore.chess.integration;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.client.ChessSchemaRegistryClient;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class ChessE2EIntegrationTest {

  private static Javalin app;
  private static String baseUrl;
  private static String schemaContent;
  private static final Map<String, String> inMemoryCasStorage = new ConcurrentHashMap<>();
  private static final Map<String, String> inMemoryRegistry = new ConcurrentHashMap<>();

  @BeforeAll
  static void startTestServer() throws Exception {
    try (InputStream is = ChessE2EIntegrationTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is, "Schema resource must exist");
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    // Spin up lightweight in-process Javalin server matching repository routes
    app = Javalin.create(config -> {}).start(0);
    baseUrl = "http://localhost:" + app.port();

    app.post("/api/v1/schemas/{name}", ctx -> {
      String contentType = ctx.contentType();
      if (contentType == null || !contentType.toLowerCase().startsWith("application/stvn")) {
        ctx.status(415).json(Map.of("error", "Unsupported Media Type"));
        return;
      }
      String schemaName = ctx.pathParam("name");
      String body = ctx.body();

      ChessBinaryCodec codec = new ChessBinaryCodec(body);
      String hashHex = codec.getCasHashHex();

      inMemoryCasStorage.put(hashHex, body);
      inMemoryRegistry.put(schemaName, hashHex);

      ctx.status(201).json(Map.of("schemaName", schemaName, "casHash", hashHex));
    });

    app.get("/api/v1/schemas/cas/{hash}", ctx -> {
      String hash = ctx.pathParam("hash");
      String payload = inMemoryCasStorage.get(hash);
      if (payload != null) {
        ctx.contentType("application/stvn").result(payload);
      } else {
        ctx.status(404);
      }
    });
  }

  @AfterAll
  static void stopTestServer() {
    if (app != null) {
      app.stop();
    }
  }

  @Test
  @DisplayName("Full E2E Pipeline: Publish schema, fetch via CAS hash, encode turns with ExplicitSha256, verify poison rejection")
  void testFullEndToEndPipeline() throws Exception {
    ChessSchemaRegistryClient client = new ChessSchemaRegistryClient(baseUrl);

    // 1. Publish Schema to Registry
    HttpResponse<String> publishResp = client.publishSchema("chess-turn-schema", schemaContent);
    assertEquals(201, publishResp.statusCode());

    // 2. Fetch Schema by CAS Hash
    ChessBinaryCodec localCodec = new ChessBinaryCodec(schemaContent);
    String casHash = localCodec.getCasHashHex();

    String fetchedSchemaText = client.fetchSchemaByCasHash(casHash);
    assertNotNull(fetchedSchemaText);
    assertEquals(schemaContent, fetchedSchemaText);

    // 3. Encode Game History using Fetched Schema
    ChessBinaryCodec remoteCodec = new ChessBinaryCodec(fetchedSchemaText);
    Move move = new Move(Square.fromAlgebraic("g1"), Square.fromAlgebraic("f3"), Optional.empty(), false, 1);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, move, "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1", 40);
    GameHistory game = new GameHistory("e2e-game-100", "Bot-A", "Bot-B", List.of(turn), Optional.of(GameHistory.TerminalOutcome.WHITE_WIN));

    ByteBuffer binaryBuffer = remoteCodec.encode(game);
    assertNotNull(binaryBuffer);

    // 4. Decode and assert isomorphic equality
    GameHistory decoded = remoteCodec.decode(binaryBuffer.duplicate());
    assertEquals(game, decoded);

    // 5. Corrupt payload and assert PoisonedRegistryPayloadException
    byte[] validBytes = new byte[binaryBuffer.remaining()];
    binaryBuffer.get(validBytes);
    byte[] poisonedBytes = ChessBinaryCodec.poisonPayload(validBytes);

    assertThrows(PoisonedRegistryPayloadException.class, () -> remoteCodec.decode(ByteBuffer.wrap(poisonedBytes)));
  }
}