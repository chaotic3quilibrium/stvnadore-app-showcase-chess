package org.stvnadore.chess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnVocabulary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated architectural hygiene suite enforcing clean-sheet STVN 2.0.0 vocabulary,
 * zero 1.x terminology or file extension remnants, and zero ANTLR CST coupling.
 */
public class StvnChessVocabularyHygieneTest {

  private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("\"([^\"]*)\"");

  private static final List<String> FORBIDDEN_RAW_TOKENS = List.of(
      // Typic primitives
      StvnVocabulary.TYPE_BOOLEAN,
      StvnVocabulary.TYPE_INT,
      StvnVocabulary.TYPE_FLOAT,
      StvnVocabulary.TYPE_STRING,
      StvnVocabulary.TYPE_TIME_EPOCH,
      StvnVocabulary.TYPE_DATE_TIME,
      StvnVocabulary.TYPE_SEQ,
      StvnVocabulary.TYPE_SET,
      StvnVocabulary.TYPE_MAP,
      StvnVocabulary.TYPE_TUPLE,
      StvnVocabulary.TYPE_OPTION,
      StvnVocabulary.TYPE_EITHER,
      StvnVocabulary.TYPE_UNION,
      StvnVocabulary.TYPE_ENUM,

      // Structural keywords
      StvnVocabulary.KEYWORD_META,
      StvnVocabulary.KEYWORD_DEFS,
      StvnVocabulary.KEYWORD_TYPE,
      StvnVocabulary.KEYWORD_BODY,
      StvnVocabulary.KEYWORD_INCLUDE,
      StvnVocabulary.KEYWORD_PACKAGE,
      StvnVocabulary.KEYWORD_USE,

      // Value tokens
      StvnVocabulary.VAL_TRUE,
      StvnVocabulary.VAL_FALSE,
      StvnVocabulary.VAL_SOME,
      StvnVocabulary.VAL_NONE,
      StvnVocabulary.VAL_LEFT,
      StvnVocabulary.VAL_RIGHT,

      // Facet keywords
      StvnVocabulary.FACET_KW_UNSIGNED,
      StvnVocabulary.FACET_KW_EXACT,
      StvnVocabulary.FACET_KW_INVERTIBLE,
      StvnVocabulary.FACET_KW_PRESERVE_INDENT,
      StvnVocabulary.FACET_KW_OFFSET,
      StvnVocabulary.FACET_KW_ZONED,
      StvnVocabulary.FACET_KW_AUDITED,
      StvnVocabulary.FACET_KW_EQUATABLE,
      StvnVocabulary.FACET_KW_COMPARABLE,
      StvnVocabulary.FACET_KW_SCALE_S,
      StvnVocabulary.FACET_KW_SCALE_MS,
      StvnVocabulary.FACET_KW_SCALE_US,
      StvnVocabulary.FACET_KW_SCALE_NS,
      StvnVocabulary.FACET_KW_SIZE,
      StvnVocabulary.FACET_KW_MIN_SIZE,
      StvnVocabulary.FACET_KW_MAX_SIZE,
      StvnVocabulary.FACET_KW_MIN_INCL,
      StvnVocabulary.FACET_KW_MIN_EXCL,
      StvnVocabulary.FACET_KW_MAX_EXCL,
      StvnVocabulary.FACET_KW_MAX_INCL,
      StvnVocabulary.FACET_KW_REGEX,
      StvnVocabulary.FACET_KW_FILTER_INCL,
      StvnVocabulary.FACET_KW_FILTER_EXCL,
      StvnVocabulary.FACET_KW_STRIP
  );

  @Test
  @DisplayName("HYGIENE-CHESS-01: Zero raw typic, facet, or structural keyword literals in src/main/java")
  void testZeroRawStringLiteralsInMainJava() throws IOException {
    Path mainSrc = Paths.get("src/main/java");
    List<String> violations = new ArrayList<>();

    try (Stream<Path> stream = Files.walk(mainSrc)) {
      List<Path> javaFiles = stream.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .toList();

      for (Path file : javaFiles) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i).trim();
          if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") || line.startsWith("import ")) {
            continue;
          }

          Matcher matcher = STRING_LITERAL_PATTERN.matcher(line);
          while (matcher.find()) {
            String literal = matcher.group(1);
            for (String forbidden : FORBIDDEN_RAW_TOKENS) {
              if (literal.equals(forbidden) ||
                  literal.contains(forbidden + " ") ||
                  literal.contains(" " + forbidden) ||
                  literal.contains(forbidden + "(") ||
                  literal.contains(forbidden + "[")) {
                violations.add(file.getFileName() + ":" + (i + 1)
                    + " contains raw literal: \"" + literal + "\" (violates token hygiene; use StvnVocabulary)");
              }
            }
          }
        }
      }
    }

    assertTrue(violations.isEmpty(),
        "Found raw token string literals in src/main/java:\n" + String.join("\n", violations));
  }

  @Test
  @DisplayName("HYGIENE-CHESS-02: Zero 1.x terminology, compound scalar tokens, or legacy extensions across src, docs, and README")
  void testZeroLegacyTerminologyAndExtensions() throws IOException {
    List<String> forbiddenLegacyTerms = List.of(
        ":Product",
        "StvnProduct",
        ":MapEntry",
        "#strict",
        "#deprecated",
        "#nonEmpty",
        ".stvn_inclf",
        ".stvn_incl",
        ".stvn_bin",
        ".stvn_f",
        "stvnInclfContent",
        "Uint4",
        "Uint7",
        "Uint10",
        "Int16",
        ":Int32",
        ":Int64",
        ":Uint8",
        ":Uint16",
        ":Uint32",
        ":Uint64",
        ":Float32",
        ":Float64",
        ":String8",
        ":String64",
        ":String128",
        ":StringFixed",
        ":StringNonEmpty",
        ":SeqNonEmpty",
        ":MapInv"
    );

    List<Path> scanTargets = new ArrayList<>();
    try (Stream<Path> srcStream = Files.walk(Paths.get("src"))) {
      srcStream.filter(Files::isRegularFile)
          .filter(p -> !p.getFileName().toString().equals("StvnChessVocabularyHygieneTest.java"))
          .filter(p -> !p.getFileName().toString().equals("StvnVocabularyHygieneTest.java"))
          .forEach(scanTargets::add);
    }
    if (Files.isDirectory(Paths.get("docs"))) {
      try (Stream<Path> docsStream = Files.walk(Paths.get("docs"))) {
        docsStream.filter(Files::isRegularFile).forEach(scanTargets::add);
      }
    }
    Path readme = Paths.get("README.md");
    if (Files.isRegularFile(readme)) {
      scanTargets.add(readme);
    }

    List<String> violations = new ArrayList<>();
    for (Path file : scanTargets) {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        for (String forbidden : forbiddenLegacyTerms) {
          if (line.contains(forbidden)) {
            violations.add(file + ":" + (i + 1) + " contains prohibited 1.x token: " + forbidden);
          }
        }
        if (line.contains(".stvn_cas") && !line.contains(".stvn_cas.stvn")) {
          violations.add(file + ":" + (i + 1) + " contains bare 1.x .stvn_cas extension");
        }
      }
    }

    assertTrue(violations.isEmpty(), "Found 1.x remnants:\n" + String.join("\n", violations));
  }

  @Test
  @DisplayName("HYGIENE-CHESS-03: Zero ANTLR CST, parser, lowering, legacy AST, or StvnTypeResolver coupling across src/main and src/test")
  void testZeroAntlrCstAndLegacyAstCoupling() throws IOException {
    List<String> forbiddenSymbols = List.of(
        "org.antlr.v4.runtime",
        "org.stvnadore.core.parser",
        "org.stvnadore.core.ast",
        "org.stvnadore.core.lowering",
        "StvnTypeResolver",
        "stvnInclfContent",
        ".stvn_inclf",
        ".stvn_incl",
        ".stvn_bin",
        ".stvn_f"
    );

    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(Paths.get("src"))) {
      List<Path> files = stream.filter(Files::isRegularFile)
          .filter(p -> !p.getFileName().toString().equals("StvnChessVocabularyHygieneTest.java"))
          .filter(p -> !p.getFileName().toString().equals("StvnVocabularyHygieneTest.java"))
          .toList();
      for (Path file : files) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          for (String forbidden : forbiddenSymbols) {
            if (line.contains(forbidden)) {
              violations.add(file + ":" + (i + 1) + " references prohibited symbol: " + forbidden);
            }
          }
        }
      }
    }

    assertTrue(violations.isEmpty(),
        "Prohibited ANTLR CST or 1.x coupling detected:\n" + String.join("\n", violations));
  }
}
