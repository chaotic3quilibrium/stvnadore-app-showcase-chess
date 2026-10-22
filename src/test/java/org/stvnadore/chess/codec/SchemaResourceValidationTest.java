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
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnTypeResolver;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated build-time validation suite verifying that all packaged STVN schema resources
 * are syntactically and semantically valid according to upstream stvnadore-core specifications.
 */
public class SchemaResourceValidationTest {

  private static final String SCHEMAS_RESOURCE_DIR = "/schemas";
  private static final String KNOWN_CANONICAL_CAS_HASH =
      "817b682a4d142ca2b6dacc3ce2e9f1460e72fc4d3b8df65a9cc8944ec91f506e";

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
  @DisplayName("All packaged schema resources must pass strict stvnadore-core lexing, parsing, and compilation")
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

    // 0. Assert zero-tab invariant (no raw horizontal tab characters)
    assertFalse(content.contains("\t"),
        "Schema " + filename + " must obey Zero-Tab Invariant: contains raw horizontal tab characters");

    // 1. Assert root wrapping rule (must begin with '{' and end with '}')
    String trimmed = content.strip();
    assertTrue(trimmed.startsWith("{"),
        "Schema " + filename + " must obey the Root Wrapping Rule: missing leading '{'");
    assertTrue(trimmed.endsWith("}"),
        "Schema " + filename + " must obey the Root Wrapping Rule: missing trailing '}'");

    // 2. Strict Parse Tree Validation (ANTLR4 fail-fast BailErrorStrategy)
    StvnParser.StvnDocumentContext docCtx = assertDoesNotThrow(
        () -> StvnCompiler.parse(content, StvnParserConfig.STRICT),
        "Schema " + filename + " must parse cleanly under strict parser configuration"
    );
    assertNotNull(docCtx, "Parse tree context must not be null for " + filename);

    // 3. Strict Monadic Compilation & Diagnostic Collection
    var result = StvnCompiler.compileToResult(
        content,
        schemaPath.toAbsolutePath().toString(),
        StvnParserConfig.STRICT
    );

    if (result.hasErrors()) {
      StringBuilder errorReport = new StringBuilder("Schema validation failed for " + filename + ":\n");
      for (StvnDiagnostic d : result.diagnostics()) {
        errorReport.append(String.format("  - Line %d, Col %d: %s\n", d.line(), d.column(), d.message()));
      }
      fail(errorReport.toString());
    }

    // 4. Semantic Type Resolution & Zero-Shadowing Enforcement
    var diagnosticBag = new DiagnosticBag();
    StvnTypeResolver.registerDocumentConfig(docCtx, StvnParserConfig.STRICT);
    Map<String, ?> definitions = StvnTypeResolver.getDocumentDefinitions(docCtx, diagnosticBag);
    assertFalse(diagnosticBag.hasErrors(),
        () -> "Semantic diagnostics reported for " + filename + ": " + diagnosticBag.toList());
    assertFalse(definitions.isEmpty(),
        "Schema " + filename + " must define at least one nominal type or constant");

    // 5. Hermetic Schema Constraint: .stvn_d files must NOT contain :include statements
    if (filename.endsWith(".stvn_d")) {
      assertNotNull(docCtx.documentBody(), "Document body must exist for " + filename);
      assertNotNull(docCtx.documentBody().defsEntry(), "Defs entry must exist for " + filename);
      var elements = docCtx.documentBody().defsEntry().defsElement();
      boolean hasInclude = elements != null && elements.stream()
          .anyMatch(elem -> elem.includeStmt() != null);
      assertFalse(hasInclude,
          "Hermetic schema (.stvn_d) " + filename + " must not contain :include statements");
    }
  }

  @Test
  @DisplayName("chess_turn.gaming.stvn_d can be cleanly imported via :include in a consumer STVN document")
  void testChessTurnSchemaIncludeResolution() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    Path schemaFilePath = Paths.get(resource.toURI()).toAbsolutePath();

    // Construct a consumer STVN document that imports chess_turn.gaming.stvn_d
    String consumerDocument = """
        {
          :defs {
            :include [ "chess_turn.gaming.stvn_d" ]
            :use [ :gaming/chess { #strip } ]
          }
          :type :GameHistory
          :body ( "import-test" "White" "Black" [] #None )
        }
        """;

    // Compile referencing the directory containing the schema for relative include resolution
    Path parentDir = schemaFilePath.getParent();
    Path virtualDocPath = parentDir.resolve("consumer_test.stvn_i");

    var result = StvnCompiler.compileToResult(
        consumerDocument,
        virtualDocPath.toString(),
        StvnParserConfig.STRICT
    );

    assertFalse(result.hasErrors(), () -> {
      StringBuilder sb = new StringBuilder("Failed to resolve :include of chess_turn.gaming.stvn_d:\n");
      for (StvnDiagnostic d : result.diagnostics()) {
        sb.append(String.format("  - Line %d, Col %d: %s\n", d.line(), d.column(), d.message()));
      }
      return sb.toString();
    });

    assertTrue(result.document().isPresent(), "Consumer document AST must compile successfully");
  }

  @Test
  @DisplayName("chess_turn.gaming.stvn_d produces invariant expected SHA-256 CAS hash")
  void testCanonicalCasHashInvariant() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    String schemaContent = Files.readString(Paths.get(resource.toURI()), StandardCharsets.UTF_8);

    ChessBinaryCodec codec = new ChessBinaryCodec(schemaContent);
    assertEquals(KNOWN_CANONICAL_CAS_HASH, codec.getCasHashHex(),
        "Schema CAS hash must remain completely invariant after remediation");
  }

  @Test
  @DisplayName("StvnSchemaFlattener flattens chess_turn.gaming.stvn_d under 5-tier Semantic Category Order")
  void testSchemaFlattenerFiveTierOrder() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    String schemaContent = Files.readString(Paths.get(resource.toURI()), StandardCharsets.UTF_8);

    String flattened = StvnSchemaFlattener.flatten(Map.of("chess_turn.gaming.stvn_d", schemaContent), "chess_turn.gaming.stvn_d");
    assertNotNull(flattened, "Flattened schema string must not be null");

    // Verify 5-tier ordering: Tier 1 (#unsigned) -> Tier 3 (#size / #minSize / #maxSize) -> Tier 4 (#minIncl / #maxExcl)
    assertTrue(flattened.contains(":gaming/chess/Rank{#unsigned #size 4 #minIncl 1 #maxExcl 9}:Int"),
        "Rank must order facets: unsigned (Tier 1) -> size (Tier 3) -> intervals (Tier 4)");
    assertTrue(flattened.contains(":gaming/chess/HalfmovesSincePawnOrCapture{#unsigned #size 7 #maxExcl 101}:Int"),
        "HalfmovesSincePawnOrCapture must order facets: unsigned (Tier 1) -> size (Tier 3) -> intervals (Tier 4)");
    assertTrue(flattened.contains(":gaming/chess/SquareIndex{#unsigned #size 6}:Int"),
        "SquareIndex must declare #unsigned #size 6 without redundant #maxExcl 64 under Rule W003");
    assertTrue(flattened.contains(":gaming/chess/TurnNumber{#unsigned #size 10 #minIncl 1}:Int"),
        "TurnNumber must order facets: unsigned (Tier 1) -> size (Tier 3) -> intervals (Tier 4)");
    assertTrue(flattened.contains(":gaming/chess/CentipawnEvaluation{#size 16}:Int"),
        "CentipawnEvaluation must declare bit-width in Tier 3");
    assertTrue(flattened.contains(":gaming/chess/SanMoveString{#minSize 1 #maxSize 8}:String"),
        "SanMoveString must declare string bounds in Tier 3");
    assertTrue(flattened.contains(":gaming/chess/FenStringFixed{#minSize 1 #maxSize 128}:String"),
        "FenStringFixed must declare string bounds in Tier 3");
    assertTrue(flattened.contains(":gaming/chess/MatchId{#minSize 1 #maxSize 64}:String"),
        "MatchId must declare string bounds in Tier 3");
    assertTrue(flattened.contains(":gaming/chess/PlayerName{#minSize 1 #maxSize 64}:String"),
        "PlayerName must declare string bounds in Tier 3");
  }

  @Test
  @DisplayName("W003 / Invariant 6: Tautological chess schemas compile with warning and yield bitwise-identical CAS hash")
  void testTautologicalSchemaCasConvergence() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.gaming.stvn_d");
    assertNotNull(resource, "Schema resource must exist");
    String cleanSchema = Files.readString(Paths.get(resource.toURI()), StandardCharsets.UTF_8);

    // Create tautological variant introducing redundant bounds on SquareIndex
    String tautologicalSchema = cleanSchema.replace(
        ":SquareIndex      { #unsigned #size 6 } :Int",
        ":SquareIndex      { #unsigned #size 6 #minIncl 0 #maxExcl 64 } :Int"
    );

    // 1. Compile clean schema: zero warnings, zero errors
    var cleanResult = StvnCompiler.compileToResult(cleanSchema, "chess_turn.gaming.stvn_d", StvnParserConfig.STRICT);
    assertTrue(cleanResult.isSuccess(), "Clean schema must compile successfully");
    assertFalse(cleanResult.hasWarnings(), () -> "Clean schema must not emit warnings: " + cleanResult.diagnostics());

    // 2. Compile tautological schema: succeeds with W003 warnings
    var tautResult = StvnCompiler.compileToResult(tautologicalSchema, "chess_turn.gaming.stvn_d", StvnParserConfig.DEFAULT);
    assertTrue(tautResult.isSuccess(), "Tautological schema must compile successfully under W003");
    assertFalse(tautResult.hasErrors(), "Tautological schema must have zero errors");
    assertTrue(tautResult.hasWarnings(), "Tautological schema must report W003 warnings");
    assertTrue(tautResult.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null))));

    // 3. Assert bitwise-identical CAS hashes across codecs
    ChessBinaryCodec cleanCodec = new ChessBinaryCodec(cleanSchema);
    ChessBinaryCodec tautCodec = new ChessBinaryCodec(tautologicalSchema);

    assertEquals(KNOWN_CANONICAL_CAS_HASH, cleanCodec.getCasHashHex());
    assertEquals(cleanCodec.getCasHashHex(), tautCodec.getCasHashHex(),
        "CAS hashes must be bitwise identical under Invariant 6 (Nominal Bijectivity)");

    // 4. Flatten preimages and assert bitwise identity under IR normalization
    String flatClean = StvnSchemaFlattener.flatten(Map.of("chess_turn.gaming.stvn_d", cleanSchema), "chess_turn.gaming.stvn_d");
    String flatTaut = StvnSchemaFlattener.flatten(Map.of("chess_turn.gaming.stvn_d", tautologicalSchema), "chess_turn.gaming.stvn_d");
    assertEquals(flatClean, flatTaut, "Flattened preimages must be bitwise identical under IR normalization");

    var md = java.security.MessageDigest.getInstance("SHA-256");
    String hashClean = java.util.HexFormat.of().formatHex(md.digest(flatClean.getBytes(StandardCharsets.UTF_8)));
    md.reset();
    String hashTaut = java.util.HexFormat.of().formatHex(md.digest(flatTaut.getBytes(StandardCharsets.UTF_8)));
    assertEquals(hashClean, hashTaut, "Repository CAS addresses of flattened preimages must be bitwise identical under Invariant 6");

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

    assertArrayEquals(cleanBuf.array(), tautBuf.array(),
        "Binary wire frames must be bitwise identical between clean and tautological schemas");

    GameHistory decodedByTaut = tautCodec.decode(cleanBuf.duplicate());
    assertEquals(game, decodedByTaut, "Tautological codec must decode payload encoded by clean codec");

    GameHistory decodedByClean = cleanCodec.decode(tautBuf.duplicate());
    assertEquals(game, decodedByClean, "Clean codec must decode payload encoded by tautological codec");
  }
}
