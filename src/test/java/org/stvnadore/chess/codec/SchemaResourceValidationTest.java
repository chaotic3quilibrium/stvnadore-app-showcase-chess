package org.stvnadore.chess.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnTypeResolver;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated build-time validation suite verifying that all packaged STVN schema resources
 * are syntactically and semantically valid according to upstream stvnadore-core specifications.
 */
public class SchemaResourceValidationTest {

  private static final String SCHEMAS_RESOURCE_DIR = "/schemas";
  private static final String KNOWN_CANONICAL_CAS_HASH =
      "f5d1bc35d228293c355deac143e52a6d3071686ceba28d343c65ab371db0af2f";

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
    return filename.endsWith(".stvn") || filename.endsWith(".stvn_incl") || filename.endsWith(".stvn_inclf");
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(
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
    Map<String, ?> definitions = StvnTypeResolver.getDocumentDefinitions(docCtx, diagnosticBag);
    assertFalse(diagnosticBag.hasErrors(),
        () -> "Semantic diagnostics reported for " + filename + ": " + diagnosticBag.toList());
    assertFalse(definitions.isEmpty(),
        "Schema " + filename + " must define at least one nominal type or constant");

    // 5. Leaf Module Constraint: .stvn_inclf files must NOT contain :include statements
    if (filename.endsWith(".stvn_inclf")) {
      assertNotNull(docCtx.documentBody(), "Document body must exist for " + filename);
      assertNotNull(docCtx.documentBody().defsEntry(), "Defs entry must exist for " + filename);
      var includes = docCtx.documentBody().defsEntry().includeStmt();
      assertTrue(includes == null || includes.isEmpty(),
          "Leaf module (.stvn_inclf) " + filename + " must not contain :include statements");
    }
  }

  @Test
  @DisplayName("chess_turn.stvn_inclf can be cleanly imported via :include in a consumer STVN document")
  void testChessTurnSchemaIncludeResolution() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.stvn_inclf");
    assertNotNull(resource, "Schema resource must exist");
    Path schemaFilePath = Paths.get(resource.toURI()).toAbsolutePath();

    // Construct a consumer STVN document that imports chess_turn.stvn_inclf
    String consumerDocument = """
        {
          :defs {
            :include [ "chess_turn.stvn_inclf" ]
          }
          :type :GameHistory
          :body ( "import-test" "White" "Black" [] #None )
        }
        """;

    // Compile referencing the directory containing the schema for relative include resolution
    Path parentDir = schemaFilePath.getParent();
    Path virtualDocPath = parentDir.resolve("consumer_test.stvn");

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(
        consumerDocument,
        virtualDocPath.toString(),
        StvnParserConfig.STRICT
    );

    assertFalse(result.hasErrors(), () -> {
      StringBuilder sb = new StringBuilder("Failed to resolve :include of chess_turn.stvn_inclf:\n");
      for (StvnDiagnostic d : result.diagnostics()) {
        sb.append(String.format("  - Line %d, Col %d: %s\n", d.line(), d.column(), d.message()));
      }
      return sb.toString();
    });

    assertTrue(result.document().isPresent(), "Consumer document AST must compile successfully");
  }

  @Test
  @DisplayName("chess_turn.stvn_inclf produces invariant expected SHA-256 CAS hash")
  void testCanonicalCasHashInvariant() throws Exception {
    var resource = getClass().getResource("/schemas/chess_turn.stvn_inclf");
    assertNotNull(resource, "Schema resource must exist");
    String schemaContent = Files.readString(Paths.get(resource.toURI()), StandardCharsets.UTF_8);

    ChessBinaryCodec codec = new ChessBinaryCodec(schemaContent);
    assertEquals(KNOWN_CANONICAL_CAS_HASH, codec.getCasHashHex(),
        "Schema CAS hash must remain completely invariant after remediation");
  }
}
