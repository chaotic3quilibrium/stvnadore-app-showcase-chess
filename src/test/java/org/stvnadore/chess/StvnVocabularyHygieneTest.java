package org.stvnadore.chess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnVocabulary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated source-level and documentation-level hygiene gate enforcing {@link StvnVocabulary}
 * single-source-of-truth compliance, zero invented or purged 1.x type literals, and Mermaid
 * diagram syntax compliance across {@code ij_stvnadore_example_chess}.
 */
class StvnVocabularyHygieneTest {

  private static final Path MAIN_JAVA_ROOT = Path.of("src/main/java/org/stvnadore/chess");
  private static final Path TEST_JAVA_ROOT = Path.of("src/test/java/org/stvnadore/chess");
  private static final Path README_PATH = Path.of("README.md");
  private static final Path DOCS_ROOT = Path.of("docs");

  /**
   * Exact STVN vocabulary string literals that must never be hardcoded in production Java files
   * outside {@link StvnVocabulary}.
   */
  private static final Set<String> FORBIDDEN_EXACT_STRING_LITERALS = Set.of(
      // Built-in types
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
      ":MapEntry",
      // Document & module keywords
      StvnVocabulary.KEYWORD_META,
      StvnVocabulary.KEYWORD_DEFS,
      StvnVocabulary.KEYWORD_TYPE,
      StvnVocabulary.KEYWORD_BODY,
      StvnVocabulary.KEYWORD_INCLUDE,
      StvnVocabulary.KEYWORD_PACKAGE,
      StvnVocabulary.KEYWORD_USE,
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
      StvnVocabulary.FACET_KW_STRIP,
      StvnVocabulary.FACET_KW_META_NAME,
      StvnVocabulary.FACET_KW_META_DOMAIN,
      StvnVocabulary.FACET_KW_META_KIND,
      // Document kind symbols
      StvnVocabulary.KIND_KW_BODY,
      StvnVocabulary.KIND_KW_DEFS,
      StvnVocabulary.KIND_KW_BODY_INCLUDE,
      StvnVocabulary.KIND_KW_DEFS_INCLUDE,
      // Built-in constant & wrapper symbols
      StvnVocabulary.VAL_TRUE,
      StvnVocabulary.VAL_FALSE,
      StvnVocabulary.VAL_TRUE_SHORT,
      StvnVocabulary.VAL_FALSE_SHORT,
      StvnVocabulary.VAL_SOME,
      StvnVocabulary.VAL_NONE,
      StvnVocabulary.VAL_SOME_SHORT,
      StvnVocabulary.VAL_NONE_SHORT,
      StvnVocabulary.VAL_LEFT,
      StvnVocabulary.VAL_RIGHT,
      StvnVocabulary.VAL_LEFT_SHORT,
      StvnVocabulary.VAL_RIGHT_SHORT,
      // Special float literals
      StvnVocabulary.LITERAL_NAN,
      StvnVocabulary.LITERAL_POS_INFINITY,
      StvnVocabulary.LITERAL_NEG_INFINITY,
      StvnVocabulary.LITERAL_NEG_ZERO,
      // Sigils
      StvnVocabulary.SIGIL_TYPIC,
      StvnVocabulary.SIGIL_VALUE
  );

  /**
   * Regex matching invented non-spec type names (:Value, :Any, :Record, :Product, :MapEntry)
   * as standalone or embedded type tokens in string literals.
   */
  private static final Pattern INVENTED_TYPE_LITERAL_PATTERN =
      Pattern.compile("(?<![A-Za-z0-9_/]):(Value|Any|Record|Product|MapEntry)(?![A-Za-z0-9_])");

  /**
   * Regex matching purged STVN 1.x temporal type tokens inside string literals.
   */
  private static final Pattern PURGED_1X_TEMPORAL_TYPE_PATTERN =
      Pattern.compile("(?<![A-Za-z0-9_/]):(DateTimeOffset|DateTimeZoned|DateTimeAudited|EpochSeconds|EpochMillis|EpochMicros|EpochNanos)(?![A-Za-z0-9_])");

  /**
   * Regex matching purged STVN 1.x temporal facet keywords in Markdown documentation.
   */
  private static final Pattern PURGED_1X_FACET_PATTERN =
      Pattern.compile("#(sec|milli|micro|nano)(?![A-Za-z0-9_])");

  @Test
  @DisplayName("TC-HYGIENE-01: Zero hardcoded STVN vocabulary string literals in src/main/java")
  void zeroHardcodedStvnVocabularyStringLiteralsInMainSource() throws IOException {
    assertTrue(FORBIDDEN_EXACT_STRING_LITERALS.contains(StvnVocabulary.SIGIL_TYPIC));
    List<String> violations = new ArrayList<>();

    try (Stream<Path> paths = Files.walk(MAIN_JAVA_ROOT)) {
      List<Path> javaFiles = paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .toList();

      for (Path file : javaFiles) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        List<ExtractedLiteral> literals = extractStringLiterals(source);
        for (ExtractedLiteral lit : literals) {
          if (FORBIDDEN_EXACT_STRING_LITERALS.contains(lit.value())) {
            violations.add(file + ":" + lit.line()
                + " -> hardcoded STVN literal \"" + lit.value()
                + "\" (use StvnVocabulary constant instead)");
          }
        }
      }
    }

    assertTrue(violations.isEmpty(),
        () -> "Found hardcoded STVN vocabulary string literals in src/main/java:\n"
            + String.join("\n", violations));
  }

  @Test
  @DisplayName("TC-HYGIENE-02: Zero invented (:Value, :Any, :Record, :Product, :MapEntry) or purged 1.x type literals in src/main/java and src/test/java")
  void zeroInventedOrPurged1xTypeLiteralsInSourceAndTests() throws IOException {
    List<String> violations = new ArrayList<>();

    for (Path root : List.of(MAIN_JAVA_ROOT, TEST_JAVA_ROOT)) {
      try (Stream<Path> paths = Files.walk(root)) {
        List<Path> javaFiles = paths
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.getFileName().toString().equals("StvnVocabularyHygieneTest.java"))
            .filter(p -> !p.getFileName().toString().equals("StvnChessVocabularyHygieneTest.java"))
            .sorted()
            .toList();

        for (Path file : javaFiles) {
          String source = Files.readString(file, StandardCharsets.UTF_8);
          List<ExtractedLiteral> literals = extractStringLiterals(source);
          for (ExtractedLiteral lit : literals) {
            Matcher inventedMatcher = INVENTED_TYPE_LITERAL_PATTERN.matcher(lit.value());
            while (inventedMatcher.find()) {
              violations.add(file + ":" + lit.line()
                  + " -> invented non-spec type literal '" + inventedMatcher.group()
                  + "' inside \"" + lit.value() + "\"");
            }
            Matcher purgedMatcher = PURGED_1X_TEMPORAL_TYPE_PATTERN.matcher(lit.value());
            while (purgedMatcher.find()) {
              violations.add(file + ":" + lit.line()
                  + " -> purged 1.x temporal type literal '" + purgedMatcher.group()
                  + "' inside \"" + lit.value() + "\"");
            }
          }
        }
      }
    }

    assertTrue(violations.isEmpty(),
        () -> "Found invented or purged 1.x STVN type literals:\n"
            + String.join("\n", violations));
  }

  @Test
  @DisplayName("TC-HYGIENE-03: Zero purged 1.x tokens, invented types, or Mermaid syntax violations in README.md and docs/")
  void zeroDocumentationOrMermaidViolations() throws IOException {
    List<Path> markdownFiles = new ArrayList<>();
    if (Files.exists(README_PATH)) {
      markdownFiles.add(README_PATH);
    }
    if (Files.exists(DOCS_ROOT)) {
      try (Stream<Path> paths = Files.walk(DOCS_ROOT)) {
        paths.filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".md"))
            .sorted()
            .forEach(markdownFiles::add);
      }
    }

    Pattern unquotedNodeLabelWithSpecialChars =
        Pattern.compile("\\b[A-Za-z0-9_]+\\[[^\"\\]]*[ ():,][^\"\\]]*\\]");

    List<String> violations = new ArrayList<>();
    for (Path mdFile : markdownFiles) {
      List<String> lines = Files.readAllLines(mdFile, StandardCharsets.UTF_8);
      boolean inMermaid = false;
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        int lineNo = i + 1;

        Matcher invented = INVENTED_TYPE_LITERAL_PATTERN.matcher(line);
        if (invented.find()) {
          violations.add(mdFile + ":" + lineNo + " -> invented type token " + invented.group());
        }
        Matcher purgedType = PURGED_1X_TEMPORAL_TYPE_PATTERN.matcher(line);
        if (purgedType.find()) {
          violations.add(mdFile + ":" + lineNo + " -> purged 1.x temporal type " + purgedType.group());
        }
        Matcher purgedFacet = PURGED_1X_FACET_PATTERN.matcher(line);
        if (purgedFacet.find()) {
          violations.add(mdFile + ":" + lineNo + " -> purged 1.x temporal facet " + purgedFacet.group());
        }

        String trimmed = line.trim();
        if (trimmed.startsWith("```mermaid")) {
          inMermaid = true;
          continue;
        }
        if (inMermaid && trimmed.startsWith("```")) {
          inMermaid = false;
          continue;
        }
        if (inMermaid) {
          if (line.contains("<br>") || line.contains("<br/>") || line.contains("\\n")) {
            violations.add(mdFile + ":" + lineNo + " -> forbidden HTML <br> or \\n escape in Mermaid block");
          }
          if (line.contains("\\\"")) {
            violations.add(mdFile + ":" + lineNo + " -> forbidden escaped quote \\\" in Mermaid block");
          }
          if (line.contains("[#quot;") || line.contains("#quot;]")) {
            violations.add(mdFile + ":" + lineNo + " -> replaces outer wrapper quotes with #quot; in Mermaid block");
          }
          Matcher unquotedMatcher = unquotedNodeLabelWithSpecialChars.matcher(line);
          if (unquotedMatcher.find()) {
            violations.add(mdFile + ":" + lineNo
                + " -> unquoted Mermaid node label with special characters: " + unquotedMatcher.group());
          }
        }
      }
    }

    assertTrue(violations.isEmpty(),
        () -> "Found documentation or Mermaid hygiene violations:\n"
            + String.join("\n", violations));
  }

  private record ExtractedLiteral(String value, int line) {}

  /**
   * Lexical scanner that extracts all standard double-quoted string literals and text blocks
   * from Java source code while ignoring line comments ({@code //}), block comments ({@code /* ... * /}),
   * and character literals ({@code '...'}).
   */
  private static List<ExtractedLiteral> extractStringLiterals(String source) {
    List<ExtractedLiteral> result = new ArrayList<>();
    int len = source.length();
    int i = 0;
    int line = 1;

    while (i < len) {
      char c = source.charAt(i);
      if (c == '\n') {
        line++;
        i++;
        continue;
      }
      // Line comment
      if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
        i += 2;
        while (i < len && source.charAt(i) != '\n') {
          i++;
        }
        continue;
      }
      // Block comment
      if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
        i += 2;
        while (i + 1 < len && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
          if (source.charAt(i) == '\n') {
            line++;
          }
          i++;
        }
        i += 2;
        continue;
      }
      // Character literal
      if (c == '\'') {
        i++;
        while (i < len && source.charAt(i) != '\'') {
          if (source.charAt(i) == '\\' && i + 1 < len) {
            i += 2;
          } else {
            if (source.charAt(i) == '\n') {
              line++;
            }
            i++;
          }
        }
        i++;
        continue;
      }
      // Text block """..."""
      if (c == '"' && i + 2 < len && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
        int startLine = line;
        i += 3;
        StringBuilder sb = new StringBuilder();
        while (i + 2 < len && !(source.charAt(i) == '"' && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"')) {
          char ch = source.charAt(i);
          if (ch == '\n') {
            line++;
          }
          if (ch == '\\' && i + 1 < len) {
            sb.append(ch).append(source.charAt(i + 1));
            if (source.charAt(i + 1) == '\n') {
              line++;
            }
            i += 2;
          } else {
            sb.append(ch);
            i++;
          }
        }
        i += 3;
        result.add(new ExtractedLiteral(sb.toString(), startLine));
        continue;
      }
      // Standard string literal "..."
      if (c == '"') {
        int startLine = line;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < len && source.charAt(i) != '"') {
          char ch = source.charAt(i);
          if (ch == '\\' && i + 1 < len) {
            char next = source.charAt(i + 1);
            switch (next) {
              case 'n' -> sb.append('\n');
              case 'r' -> sb.append('\r');
              case 't' -> sb.append('\t');
              case '"' -> sb.append('"');
              case '\\' -> sb.append('\\');
              default -> sb.append(next);
            }
            i += 2;
          } else {
            if (ch == '\n') {
              line++;
            }
            sb.append(ch);
            i++;
          }
        }
        i++;
        result.add(new ExtractedLiteral(sb.toString(), startLine));
        continue;
      }
      i++;
    }
    return result;
  }
}
