package org.stvnadore.chess.fuzz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDocument;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Zero-Tab Ingress Hardening (E001 / ERR_TAB_CHARACTER_FORBIDDEN)")
class TabCharacterIngressTest {

  @Test
  @DisplayName("Horizontal tab in chess_turn.gaming.stvn_d emits E001 at exact [tabIdx, tabIdx + 1) coordinate without ANTLR jargon")
  void testTabInChessSchemaEmitsExactE001Coordinate() throws Exception {
    String validSchema;
    try (InputStream is = TabCharacterIngressTest.class.getResourceAsStream("/schemas/chess_turn.gaming.stvn_d")) {
      assertNotNull(is);
      validSchema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    int targetAnchor = validSchema.indexOf(":Rank");
    String taintedSchema = validSchema.substring(0, targetAnchor) + "\t" + validSchema.substring(targetAnchor);

    for (StvnParserConfig config : new StvnParserConfig[]{StvnParserConfig.DEFAULT, StvnParserConfig.STRICT}) {
      StvnCompilationResult<StvnDocument> result =
          StvnCompiler.compile(taintedSchema, "chess_turn.gaming.stvn_d", config);
      assertTrue(result.hasErrors());
      StvnDiagnostic diag = result.diagnostics().getFirst();
      assertEquals(Optional.of("E001"), diag.canonicalCode());
      assertEquals(Optional.of("ERR_TAB_CHARACTER_FORBIDDEN"), diag.symbolicId());
      assertEquals(targetAnchor, diag.startOffset());
      assertEquals(targetAnchor + 1, diag.endOffset());
      assertZeroCompilerJargon(diag.message());
    }
  }

  @Test
  @DisplayName("Horizontal tab in multi-turn :GameHistory payload pins exact E001 span while tab escape inside string succeeds")
  void testTabInChessGamePayloadVsEscapedTabInString() throws Exception {
    String validSchema;
    try (InputStream is = TabCharacterIngressTest.class.getResourceAsStream("/schemas/chess_turn.gaming.stvn_d")) {
      assertNotNull(is);
      validSchema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
    String defsOnly = extractDefsWithUse(validSchema);

    String taintedPayload = "{\n  " + defsOnly
        + "\n  :type :GameHistory\n"
        + "  :body (\n"
        + "    \"opera-1858\" \"Paul Morphy\" \"Duke Karl\"\n"
        + "    [\n"
        + "      ( 1 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 0 ) \"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR\" 25 )\n"
        + "\t     ( 2 #BLACK ( ( #E 7 ) ( #E 5 ) #None #FALSE 0 ) \"rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR\" 20 )\n"
        + "    ]\n"
        + "    #None\n"
        + "  )\n}";

    int tabIdx = taintedPayload.indexOf('\t');
    StvnCompilationResult<StvnDocument> result =
        StvnCompiler.compile(taintedPayload, "opera_tainted.gaming.stvn", StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    StvnDiagnostic diag = result.diagnostics().getFirst();
    assertEquals(Optional.of("E001"), diag.canonicalCode());
    assertEquals(Optional.of("ERR_TAB_CHARACTER_FORBIDDEN"), diag.symbolicId());
    assertEquals(tabIdx, diag.startOffset());
    assertEquals(tabIdx + 1, diag.endOffset());
    assertZeroCompilerJargon(diag.message());

    // Escaped \t inside a simple quoted string literal is legal STVN 2.0.0
    String validEscapedTabPayload = "{\n  " + defsOnly
        + "\n  :type :GameHistory\n"
        + "  :body (\n"
        + "    \"opera-1858\" \"Paul\\tMorphy\" \"Duke Karl\"\n"
        + "    [\n"
        + "      ( 1 #WHITE ( ( #E 2 ) ( #E 4 ) #None #FALSE 0 ) \"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR\" 25 )\n"
        + "    ]\n"
        + "    #None\n"
        + "  )\n}";
    StvnCompilationResult<StvnDocument> cleanResult =
        StvnCompiler.compile(validEscapedTabPayload, "opera_clean.gaming.stvn", StvnParserConfig.STRICT);
    assertFalse(cleanResult.hasErrors(), () -> "Escaped \\t inside string literal must succeed: " + cleanResult.diagnostics());
  }

  private static String extractDefsWithUse(String schema) {
    int defsIdx = schema.indexOf(StvnVocabulary.KEYWORD_DEFS);
    int openBrace = schema.indexOf('{', defsIdx);
    int depth = 0;
    int closeBrace = -1;
    for (int i = openBrace; i < schema.length(); i++) {
      char c = schema.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          closeBrace = i;
          break;
        }
      }
    }
    String defsBlock = schema.substring(defsIdx, closeBrace + 1);
    int lastBrace = defsBlock.lastIndexOf('}');
    return defsBlock.substring(0, lastBrace)
        + "  " + StvnVocabulary.KEYWORD_USE + " [ :gaming/chess { " + StvnVocabulary.FACET_KW_STRIP + " } ]\n  }";
  }

  private static void assertZeroCompilerJargon(String message) {
    String lower = message.toLowerCase(Locale.ROOT);
    assertFalse(lower.contains("token recognition error"), () -> "Leaked ANTLR jargon: " + message);
    assertFalse(lower.contains("lexernoviablealtexception"), () -> "Leaked ANTLR jargon: " + message);
    assertFalse(lower.contains("no viable alternative"), () -> "Leaked ANTLR jargon: " + message);
    assertFalse(lower.contains("mismatched input"), () -> "Leaked ANTLR jargon: " + message);
  }
}
