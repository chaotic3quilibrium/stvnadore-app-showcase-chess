package org.stvnadore.chess.fuzz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated test suite verifying fail-closed rejection of documents containing
 * raw horizontal tab characters (U+0009) with ERR_TAB_CHARACTER_FORBIDDEN.
 */
public class TabCharacterIngressTest {

  @Test
  @DisplayName("Strict compilation immediately throws exception on tab character")
  void testStrictIngressRejectsTabCharacter() {
    String tabbedDocument = "{\n\t:defs {\n\t\t:Move :Int32\n\t}\n\t:type :Move\n\t:body 42\n}\n";

    var exception = assertThrows(RuntimeException.class, () ->
        StvnCompiler.compile(tabbedDocument)
    );

    assertTrue(exception.getMessage().contains("ERR_TAB_CHARACTER_FORBIDDEN"),
        "Cancellation exception message must explicitly reference ERR_TAB_CHARACTER_FORBIDDEN");
    assertTrue(exception.getMessage().contains("U+0009"),
        "Exception message must identify U+0009 tab code point");

    // Also assert StvnCompiler.parse throws ParseCancellationException directly under strict BailErrorStrategy
    assertThrows(org.antlr.v4.runtime.misc.ParseCancellationException.class, () ->
        StvnCompiler.parse(tabbedDocument, StvnParserConfig.STRICT)
    );
  }

  @Test
  @DisplayName("Accumulating compilation reports ERR_TAB_CHARACTER_FORBIDDEN diagnostic")
  void testAccumulatingIngressReportsTabDiagnostic() {
    String tabbedDocument = "{\n\t:type :Int32\n\t:body 100\n}\n";
    StvnParserConfig nonStrictConfig = new StvnParserConfig(false, 100);

    var result = StvnCompiler.compileToResult(tabbedDocument, "test_tab.stvn", nonStrictConfig);

    assertTrue(result.hasErrors(), "Result must contain compilation errors");
    boolean hasTabCode = result.diagnostics().stream()
        .anyMatch(d -> d.errorCode().map("ERR_TAB_CHARACTER_FORBIDDEN"::equals).orElse(false));
    assertTrue(hasTabCode, "Diagnostics must contain ERR_TAB_CHARACTER_FORBIDDEN error code");
  }
}
