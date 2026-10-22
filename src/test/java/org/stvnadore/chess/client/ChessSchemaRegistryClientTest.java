package org.stvnadore.chess.client;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ChessSchemaRegistryClientTest {

  private static Javalin app;
  private static String baseUrl;
  private static final String CAS_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String SCHEMA_BODY = "{\n  :defs {\n    :Test :Int\n  }\n}";

  @BeforeAll
  static void setUp() {
    app = Javalin.create(config -> {}).start(0);
    baseUrl = "http://localhost:" + app.port();

    app.post("/api/v1/schemas/{name}", ctx -> {
      String contentType = ctx.contentType();
      if (contentType == null || !contentType.toLowerCase().startsWith("application/stvn")) {
        ctx.status(415).json(Map.of("error", "Unsupported Media Type"));
        return;
      }
      if (ctx.body().contains("#maxExcl 64")) {
        ctx.header("X-STVN-Warnings-Count", "1");
      }
      ctx.status(201).json(Map.of(
          "schemaName", ctx.pathParam("name"),
          "shapeSignature", "test-signature",
          "casHash", CAS_HASH
      ));
    });

    app.get("/api/v1/schemas/cas/{hash}", ctx -> {
      if (CAS_HASH.equals(ctx.pathParam("hash"))) {
        ctx.contentType("application/stvn").result(SCHEMA_BODY);
      } else {
        ctx.status(404);
      }
    });
  }

  @AfterAll
  static void tearDown() {
    if (app != null) {
      app.stop();
    }
  }

  @Test
  @DisplayName("Client successfully publishes schema and retrieves raw payload by CAS hash")
  void testPublishAndFetch() throws Exception {
    ChessSchemaRegistryClient client = new ChessSchemaRegistryClient(baseUrl);

    PublishResultDto publishResult = client.publishSchema("test-schema", SCHEMA_BODY);
    assertEquals("test-schema", publishResult.schemaName());
    assertEquals(CAS_HASH, publishResult.casHash());
    assertEquals(0, publishResult.warningsCount(), "Clean schema must report 0 warnings");

    String tautologicalBody = "{\n  :defs {\n    :SquareIndex { #unsigned #size 6 #maxExcl 64 } :Int\n  }\n}";
    PublishResultDto tautResult = client.publishSchema("taut-schema", tautologicalBody);
    assertEquals("taut-schema", tautResult.schemaName());
    assertEquals(CAS_HASH, tautResult.casHash());
    assertEquals(1, tautResult.warningsCount(), "Tautological schema must report 1 warning from header");

    String fetched = client.fetchSchemaByCasHash(CAS_HASH);
    assertEquals(SCHEMA_BODY, fetched);

    assertThrows(IllegalStateException.class, () ->
        client.fetchSchemaByCasHash("0000000000000000000000000000000000000000000000000000000000000000"));
  }
}
