package org.stvnadore.chess.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated build-time validation suite verifying that all packaged STVN schema resources
 * compile cleanly into pure-IR {@link StvnDocument} catalogs and match frozen SHA-256 digests.
 */
public class SchemaResourceValidationTest {

  private static final String SCHEMAS_RESOURCE_DIR = "/schemas";
  private static final String CANONICAL_SCHEMA_FILENAME = "chess_turn.gaming.stvn_d";
  private static final String KNOWN_CANONICAL_CAS_HASH =
      "2e4b82fdf50c9245ecdbfda202af932dd86c8e1596c929f18c4760aa2698999a";
  private static final String KNOWN_REPOSITORY_FLATTENED_CAS_HASH =
      "b5f53562247e08e093b75e795ea1f8e85d3c80cf297b4958f1fce718f07a5837";

  /**
   * Discovers all packaged schema resource paths across the project.
   */
  static List<Path> discoverSchemaResourcePaths() throws Exception {
    var resourceUrl = SchemaResourceValidationTest.class.getResource(SCHEMAS_RESOURCE_DIR);
    assertNotNull(resourceUrl, "Resource directory /schemas must exist on classpath");

    URI uri = resourceUrl.toURI();
    if ("jar".equals(uri.getScheme())) {
      try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
        Path jarPath = fs.getPath(SCHEMAS_RESOURCE_DIR);
        try (Stream<Path> stream = Files.walk(jarPath)) {
          return stream
              .filter(Files::isRegularFile)
              .filter(SchemaResourceValidationTest::isStvnSchemaFile)
              .toList();
        }
      }
    } else {
      Path dirPath = Paths.get(uri);
      try (Stream<Path> stream = Files.walk(dirPath)) {
        return stream
            .filter(Files::isRegularFile)
            .filter(SchemaResourceValidationTest::isStvnSchemaFile)
            .toList();
      }
    }
  }

  private static boolean isStvnSchemaFile(Path path) {
    String filename = path.getFileName().toString();
    return filename.endsWith(".stvn_d") || filename.endsWith(".stvn_di");
  }

  @Test
  @DisplayName("All packaged schema resources compile cleanly to pure-IR StvnDocument with zero errors and zero W003 warnings")
  void testPackagedSchemaResourcesAreStrictlyValid() throws Exception {
    List<Path> schemaPaths = discoverSchemaResourcePaths();
    assertFalse(schemaPaths.isEmpty(), "At least one schema resource must be discovered in /schemas");

    for (Path schemaPath : schemaPaths) {
      validateSchemaResource(schemaPath);
    }
  }

  private void validateSchemaResource(Path schemaPath) throws IOException {
    String content = Files.readString(schemaPath, StandardCharsets.UTF_8);
    String filename = schemaPath.getFileName().toString();

    // 1. Assert zero-tab invariant (no raw horizontal tab characters)
    assertFalse(content.contains("\t"),
        "Schema " + filename + " must obey Zero-Tab Invariant: contains raw horizontal tab characters");

    // 2. Assert root wrapping rule (must begin with '{' and end with '}')
    String trimmed = content.strip();
    assertTrue(trimmed.startsWith("{"),
        "Schema " + filename + " must obey the Root Wrapping Rule: missing leading '{'");
    assertTrue(trimmed.endsWith("}"),
        "Schema " + filename + " must obey the Root Wrapping Rule: missing trailing '}'");

    // 3. Strict Pure-IR compilation without ANTLR CST coupling
    StvnCompilationResult<StvnDocument> compiled = assertDoesNotThrow(
        () -> StvnCompiler.compile(content, schemaPath.toAbsolutePath().toString(), StvnParserConfig.STRICT),
        () -> "Schema failed strict syntactic compilation: " + schemaPath
    );

    if (compiled.hasErrors()) {
      StringBuilder errorReport = new StringBuilder("Schema validation failed for " + filename + ":\n");
      for (StvnDiagnostic d : compiled.diagnostics()) {
        errorReport.append(String.format("  - [%s / %s] Line %d, Col %d: %s%n",
            d.canonicalCode().orElse("-"), d.symbolicId().orElse("-"), d.line(), d.column(), d.message()));
      }
      fail(errorReport.toString());
    }

    StvnDocument doc = compiled.document().orElseThrow(
        () -> new AssertionError("Missing pure-IR StvnDocument for " + schemaPath)
    );
    assertTrue(doc.hasMeta(), () -> "Missing :meta block in " + schemaPath);
    assertTrue(doc.hasDefsSection(), () -> "Missing :defs block in " + schemaPath);
    assertFalse(doc.defs().nominalTypes().isEmpty(), () -> "Schema declared zero nominal types: " + schemaPath);

    if (filename.equals(CANONICAL_SCHEMA_FILENAME)) {
      assertEquals(Optional.of(StvnDocumentKind.DEFS), doc.requireMeta().kind(),
          () -> "Expected #kind #DEFS in " + schemaPath);
      assertEquals(Optional.of("chess_turn"), doc.requireMeta().name());
      assertEquals(Optional.of("gaming"), doc.requireMeta().domain());
      assertTrue(doc.defs().findType(":gaming/chess/GameHistory").isPresent(),
          "Missing :gaming/chess/GameHistory in catalog");
    }

    // 4. Hermetic .stvn_d resource discipline: zero :include, :type, or :body
    if (filename.endsWith(".stvn_d")) {
      assertFalse(doc.hasIncludes(),
          () -> ".stvn_d resource must not contain :include directives: " + schemaPath);
      assertFalse(doc.hasRootType(),
          () -> ".stvn_d resource must not contain :type: " + schemaPath);
      assertFalse(doc.hasPayload(),
          () -> ".stvn_d resource must not contain :body: " + schemaPath);
    }

    // 5. Enforce zero W003 (WARN_REDUNDANT_METADATA_FACET) tautological facet warnings
    List<StvnDiagnostic> w003Warnings = compiled.diagnostics().stream()
        .filter(d -> d.canonicalCode().filter("W003"::equals).isPresent()
            || d.symbolicId().filter("WARN_REDUNDANT_METADATA_FACET"::equals).isPresent())
        .toList();
    assertTrue(
        w003Warnings.isEmpty(),
        () -> "Schema " + filename + " emitted W003 tautological facet warnings: " + w003Warnings
    );
  }

  @Test
  @DisplayName("chess_turn.gaming.stvn_d can be cleanly imported via :include in a consumer STVN document")
  void testChessTurnSchemaIncludeResolution() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    Path schemaFilePath = Paths.get(resource.toURI()).toAbsolutePath();

    String consumerDocument = """
        {
          :meta { #name "consumer_test" #domain "gaming" #kind #BODY_INCLUDE }
          :defs {
            :include [ "chess_turn.gaming.stvn_d" ]
            :use [ :gaming/chess { #strip } ]
          }
          :type :GameHistory
          :body ( "import-test" "White" "Black" [] #None )
        }
        """;

    Path parentDir = schemaFilePath.getParent();
    Path virtualDocPath = parentDir.resolve("consumer_test.gaming.stvn_i");

    StvnCompilationResult<StvnDocument> result = StvnCompiler.compile(
        consumerDocument,
        virtualDocPath.toString(),
        StvnParserConfig.STRICT
    );

    assertFalse(result.hasErrors(), () -> {
      StringBuilder sb = new StringBuilder("Failed to resolve :include of chess_turn.gaming.stvn_d:\n");
      for (StvnDiagnostic d : result.diagnostics()) {
        sb.append(String.format("  - Line %d, Col %d: %s%n", d.line(), d.column(), d.message()));
      }
      return sb.toString();
    });

    assertTrue(result.document().isPresent(), "Consumer pure-IR StvnDocument must compile cleanly");
    assertTrue(result.document().get().hasPayload(), "Consumer document must lower :body payload");
  }

  @Test
  @DisplayName("Canonical chess_turn.gaming.stvn_d pins both root wire SHA-256 and flattened repository CAS SHA-256")
  void testDeterministicCasHashStability() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    String canonicalSchemaContent = Files.readString(Paths.get(resource.toURI()), StandardCharsets.UTF_8);

    ChessBinaryCodec codec1 = new ChessBinaryCodec(canonicalSchemaContent);
    ChessBinaryCodec codec2 = new ChessBinaryCodec(canonicalSchemaContent);

    assertEquals(64, codec1.getCasHashHex().length(), "CAS hash must be 64 lowercase hex characters");
    assertEquals(codec1.getCasHashHex(), codec2.getCasHashHex(), "CAS hash must be deterministic across runs");
    assertEquals(
        KNOWN_CANONICAL_CAS_HASH,
        codec1.getCasHashHex(),
        "Root :GameHistory wire SHA-256 drifted! Verify intentional schema change before updating."
    );

    String flattened = StvnSchemaFlattener.flatten(
        Map.of(CANONICAL_SCHEMA_FILENAME, canonicalSchemaContent),
        CANONICAL_SCHEMA_FILENAME
    );
    String flattenedSha256 = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(flattened.getBytes(StandardCharsets.UTF_8))
    );
    assertEquals(KNOWN_REPOSITORY_FLATTENED_CAS_HASH, flattenedSha256);
    assertEquals(
        KNOWN_REPOSITORY_FLATTENED_CAS_HASH,
        codec1.getRepositoryCasHashHex(),
        "Flattened repository .stvn_d CAS SHA-256 drifted!"
    );
  }

  @Test
  @DisplayName("StvnSchemaFlattener flattens chess_turn.gaming.stvn_d under 5-tier Decimal Metadata Facet Order")
  void testSchemaFlattenerFiveTierOrder() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    String schemaContent = Files.readString(Paths.get(resource.toURI()), StandardCharsets.UTF_8);

    String flattened = StvnSchemaFlattener.flatten(
        Map.of(CANONICAL_SCHEMA_FILENAME, schemaContent),
        CANONICAL_SCHEMA_FILENAME
    );
    assertNotNull(flattened, "Flattened schema string must not be null");

    assertTrue(flattened.contains(":gaming/chess/Rank{#unsigned #size 4 #minIncl 1 #maxExcl 9}:Int"),
        "Rank must order facets strictly by 5-tier decimal sequence");
    assertTrue(flattened.contains(":gaming/chess/HalfmovesSincePawnOrCapture{#unsigned #size 7 #maxExcl 101}:Int"),
        "HalfmovesSincePawnOrCapture must order facets strictly by 5-tier decimal sequence");
    assertTrue(flattened.contains(":gaming/chess/SquareIndex{#unsigned #size 6}:Int"),
        "SquareIndex must declare #unsigned #size 6 without redundant bounds under Rule W003");
    assertTrue(flattened.contains(":gaming/chess/TurnNumber{#unsigned #size 10 #minIncl 1}:Int"),
        "TurnNumber must order facets strictly by 5-tier decimal sequence");
    assertTrue(flattened.contains(":gaming/chess/CentipawnEvaluation{#size 16}:Int"),
        "CentipawnEvaluation must declare signed 16-bit width");
    assertTrue(flattened.contains(":gaming/chess/SanMoveString{#minSize 1 #maxSize 8}:String"),
        "SanMoveString must declare character cardinality bounds");
    assertTrue(flattened.contains(":gaming/chess/FenStringFixed{#minSize 1 #maxSize 128}:String"),
        "FenStringFixed must declare character cardinality bounds");
    assertTrue(flattened.contains(":gaming/chess/MatchId{#minSize 1 #maxSize 64}:String"),
        "MatchId must declare character cardinality bounds");
    assertTrue(flattened.contains(":gaming/chess/PlayerName{#minSize 1 #maxSize 64}:String"),
        "PlayerName must declare character cardinality bounds");
  }

  @Test
  @DisplayName("W003 / Invariant 6: Tautological chess schemas compile with W003 warning and yield bitwise-identical CAS digests")
  void testTautologicalSchemaCasConvergence() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    String cleanSchema = Files.readString(Paths.get(resource.toURI()), StandardCharsets.UTF_8);

    String tautologicalSchema = cleanSchema.replace(
        ":SquareIndex      { #unsigned #size 6 } :Int",
        ":SquareIndex      { #unsigned #size 6 #minIncl 0 #maxExcl 64 } :Int"
    );

    // 1. Compile clean schema: zero warnings, zero errors
    StvnCompilationResult<StvnDocument> cleanResult =
        StvnCompiler.compile(cleanSchema, CANONICAL_SCHEMA_FILENAME, StvnParserConfig.STRICT);
    assertTrue(cleanResult.isSuccess(), "Clean schema must compile successfully");
    assertFalse(cleanResult.hasWarnings(), () -> "Clean schema must not emit warnings: " + cleanResult.diagnostics());

    // 2. Compile tautological schema: succeeds with W003 / WARN_REDUNDANT_METADATA_FACET warnings
    StvnCompilationResult<StvnDocument> tautResult =
        StvnCompiler.compile(tautologicalSchema, CANONICAL_SCHEMA_FILENAME, StvnParserConfig.DEFAULT);
    assertTrue(tautResult.isSuccess(), "Tautological schema must compile successfully under W003");
    assertFalse(tautResult.hasErrors(), "Tautological schema must have zero errors");
    assertTrue(tautResult.hasWarnings(), "Tautological schema must report W003 warnings");
    assertTrue(tautResult.diagnostics().stream().anyMatch(d ->
        d.canonicalCode().equals(Optional.of("W003"))
            && d.symbolicId().equals(Optional.of("WARN_REDUNDANT_METADATA_FACET"))));

    // 3. Assert bitwise-identical wire and repository CAS hashes across codecs
    ChessBinaryCodec cleanCodec = new ChessBinaryCodec(cleanSchema);
    ChessBinaryCodec tautCodec = new ChessBinaryCodec(tautologicalSchema);

    assertEquals(KNOWN_CANONICAL_CAS_HASH, cleanCodec.getCasHashHex());
    assertEquals(cleanCodec.getCasHashHex(), tautCodec.getCasHashHex(),
        "Wire SHA-256 digests must be bitwise identical under Invariant 6");
    assertEquals(KNOWN_REPOSITORY_FLATTENED_CAS_HASH, cleanCodec.getRepositoryCasHashHex());
    assertEquals(cleanCodec.getRepositoryCasHashHex(), tautCodec.getRepositoryCasHashHex(),
        "Flattened repository CAS digests must be bitwise identical under Invariant 6");

    // 4. Flatten preimages and assert bitwise identity under IR normalization
    String flatClean = StvnSchemaFlattener.flatten(Map.of(CANONICAL_SCHEMA_FILENAME, cleanSchema), CANONICAL_SCHEMA_FILENAME);
    String flatTaut = StvnSchemaFlattener.flatten(Map.of(CANONICAL_SCHEMA_FILENAME, tautologicalSchema), CANONICAL_SCHEMA_FILENAME);
    assertEquals(flatClean, flatTaut, "Flattened preimages must be bitwise identical under IR normalization");

    // 5. Invariant 8 Wire Framing Parity: test cross-codec encode and decode
    Move move = new Move(
        Square.fromAlgebraic("e2"),
        Square.fromAlgebraic("e4"),
        Optional.empty(),
        false,
        0
    );
    TurnState turn = new TurnState(
        1,
        Piece.PieceColor.WHITE,
        move,
        "fen-cross-codec",
        20
    );
    GameHistory game = new GameHistory("cross-codec-game", "W", "B", List.of(turn), Optional.empty());

    ByteBuffer cleanBuf = cleanCodec.encode(game);
    ByteBuffer tautBuf = tautCodec.encode(game);

    byte[] cleanBytes = new byte[cleanBuf.remaining()];
    cleanBuf.duplicate().get(cleanBytes);
    byte[] tautBytes = new byte[tautBuf.remaining()];
    tautBuf.duplicate().get(tautBytes);
    assertArrayEquals(cleanBytes, tautBytes,
        "Binary wire frames must be bitwise identical between clean and tautological schemas");

    GameHistory decodedByTaut = tautCodec.decode(cleanBuf.duplicate());
    assertEquals(game, decodedByTaut, "Tautological codec must decode payload encoded by clean codec");

    GameHistory decodedByClean = cleanCodec.decode(tautBuf.duplicate());
    assertEquals(game, decodedByClean, "Clean codec must decode payload encoded by tautological codec");
  }
}
