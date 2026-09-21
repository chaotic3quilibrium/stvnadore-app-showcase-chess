package org.stvnadore.chess.codec;

import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Lossless bidirectional mapper between GameHistory domain records and STVN AST representations.
 */
public final class ChessAstMapper {

  private ChessAstMapper() {
    // Utility class
  }

  /**
   * Serializes a GameHistory record into a valid STVN document body string.
   *
   * @param game the game history to format
   * @param schemaContent raw schema definition containing :defs
   * @return complete STVN document text
   */
  public static String toStvnDocument(GameHistory game, String schemaContent) {
    Objects.requireNonNull(game, "game must not be null");
    Objects.requireNonNull(schemaContent, "schemaContent must not be null");

    String defsBlock = extractDefsBlock(schemaContent);
    if (defsBlock.contains(":package :org/stvnadore/chess")) {
      int lastBrace = defsBlock.lastIndexOf('}');
      defsBlock = defsBlock.substring(0, lastBrace) + "  :use [ :org/stvnadore/chess { #strip } ]\n  }";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append(defsBlock).append("\n\n");
    sb.append("  :type :GameHistory\n");
    sb.append("  :body (\n");
    sb.append("    \"").append(escape(game.gameId())).append("\"\n");
    sb.append("    \"").append(escape(game.whitePlayer())).append("\"\n");
    sb.append("    \"").append(escape(game.blackPlayer())).append("\"\n");
    sb.append("    [\n");

    for (TurnState turn : game.turns()) {
      sb.append("      (\n");
      sb.append("        ").append(turn.turnNumber()).append("\n");
      sb.append("        #").append(turn.activeColor().name()).append("\n");
      sb.append("        (\n");
      sb.append("          ( #").append(turn.move().from().file().name()).append(" ").append(turn.move().from().rank()).append(" )\n");
      sb.append("          ( #").append(turn.move().to().file().name()).append(" ").append(turn.move().to().rank()).append(" )\n");
      if (turn.move().promotion().isPresent()) {
        sb.append("          #Some #").append(turn.move().promotion().get().name()).append("\n");
      } else {
        sb.append("          #None\n");
      }
      sb.append("          ").append(turn.move().isCapture() ? "#TRUE" : "#FALSE").append("\n");
      sb.append("          ").append(turn.move().halfmovesSincePawnOrCapture()).append("\n");
      sb.append("        )\n");
      sb.append("        \"").append(escape(turn.fen())).append("\"\n");
      sb.append("        ").append(turn.evaluationCentipawns()).append("\n");
      sb.append("      )\n");
    }

    sb.append("    ]\n");

    if (game.result().isPresent()) {
      sb.append("    #Some #").append(game.result().get().name()).append("\n");
    } else {
      sb.append("    #None\n");
    }

    sb.append("  )\n");
    sb.append("}\n");

    return sb.toString();
  }

  /**
   * Compiles a GameHistory record directly into a validated StvnValue AST.
   *
   * @param game the game history to format
   * @param schemaContent raw schema definition containing :defs
   * @return compiled StvnValue AST
   */
  public static StvnValue toStvnAst(GameHistory game, String schemaContent) {
    String docText = toStvnDocument(game, schemaContent);
    return StvnCompiler.compile(docText)
        .orElseThrow(() -> new IllegalStateException("Failed to compile GameHistory into valid STVN AST"));
  }

  /**
   * Decodes an unpacked StvnValue AST back into an immutable GameHistory domain record.
   *
   * @param ast unpacked root StvnValue AST
   * @return domain GameHistory instance
   */
  public static GameHistory fromStvnAst(StvnValue ast) {
    Objects.requireNonNull(ast, "ast must not be null");
    assertNominalSchema(ast, "GameHistory", "root");
    StvnValue.StvnTuple rootTuple = assertTuple(ast, 5, "root");

    String gameId = unescape(assertString(rootTuple.elements().get(0), "root.gameId").value());
    String whitePlayer = unescape(assertString(rootTuple.elements().get(1), "root.whitePlayer").value());
    String blackPlayer = unescape(assertString(rootTuple.elements().get(2), "root.blackPlayer").value());

    StvnValue.StvnSeq turnsSeq = assertSeq(rootTuple.elements().get(3), "root.turns");
    List<TurnState> turns = new ArrayList<>();

    for (int i = 0; i < turnsSeq.elements().size(); i++) {
      StvnValue turnVal = turnsSeq.elements().get(i);
      String turnPath = "turns[" + i + "]";
      assertNominalSchema(turnVal, "TurnState", turnPath);
      StvnValue.StvnTuple turnTuple = assertTuple(turnVal, 5, turnPath);

      long turnNumber = assertInteger(turnTuple.elements().get(0), turnPath + ".turnNumber").value().longValue();
      String colorStr = stripHash(assertEnum(turnTuple.elements().get(1), turnPath + ".color").keyword());
      Piece.PieceColor activeColor = Piece.PieceColor.valueOf(colorStr.toUpperCase(Locale.ROOT));

      StvnValue moveVal = turnTuple.elements().get(2);
      String movePath = turnPath + ".move";
      assertNominalSchema(moveVal, "Move", movePath);
      StvnValue.StvnTuple moveTuple = assertTuple(moveVal, 5, movePath);

      Square from = mapSquare(moveTuple.elements().get(0), movePath + ".from");
      Square to = mapSquare(moveTuple.elements().get(1), movePath + ".to");

      StvnValue.StvnOption promoOpt = assertOption(moveTuple.elements().get(2), movePath + ".promotion");
      Optional<Move.PromotionRole> promo = promoOpt.value().map(v -> {
        StvnValue.StvnEnum promoEnum = assertEnum(v, movePath + ".promotion.role");
        return Move.PromotionRole.valueOf(stripHash(promoEnum.keyword()).toUpperCase(Locale.ROOT));
      });

      boolean isCapture = assertBoolean(moveTuple.elements().get(3), movePath + ".isCapture").value();
      int halfmoves = assertInteger(moveTuple.elements().get(4), movePath + ".halfmoves").value().intValue();

      Move move = new Move(from, to, promo, isCapture, halfmoves);
      String fen = unescape(assertString(turnTuple.elements().get(3), turnPath + ".fen").value());
      int turnEval = assertInteger(turnTuple.elements().get(4), turnPath + ".eval").value().intValue();

      turns.add(new TurnState(turnNumber, activeColor, move, fen, turnEval));
    }

    StvnValue.StvnOption resultOpt = assertOption(rootTuple.elements().get(4), "root.result");
    Optional<GameHistory.TerminalOutcome> result = resultOpt.value().map(v -> {
      StvnValue.StvnEnum resultEnum = assertEnum(v, "root.result.outcome");
      return GameHistory.TerminalOutcome.valueOf(stripHash(resultEnum.keyword()).toUpperCase(Locale.ROOT));
    });

    return new GameHistory(gameId, whitePlayer, blackPlayer, turns, result);
  }

  private static Square mapSquare(StvnValue squareVal, String path) {
    assertNominalSchema(squareVal, "Square", path);
    StvnValue.StvnTuple squareTuple = assertTuple(squareVal, 2, path);
    String fileStr = stripHash(assertEnum(squareTuple.elements().get(0), path + ".file").keyword());
    Square.File file = Square.File.valueOf(fileStr.toUpperCase(Locale.ROOT));
    int rank = assertInteger(squareTuple.elements().get(1), path + ".rank").value().intValue();
    return new Square(file, rank);
  }

  private static void assertNominalSchema(StvnValue node, String expectedSuffix, String path) {
    String alias = node.schema().aliasName().orElse(null);
    if (alias == null || (!alias.equals(":" + expectedSuffix) && !alias.endsWith("/" + expectedSuffix))) {
      throw new NominalSchemaMismatchException(":" + expectedSuffix, alias, path, node);
    }
  }

  private static StvnValue.StvnTuple assertTuple(StvnValue node, int size, String path) {
    if (!(node instanceof StvnValue.StvnTuple t) || t.elements().size() != size) {
      String actual = node instanceof StvnValue.StvnTuple t2 ? "Tuple of arity " + t2.elements().size() : node.getClass().getSimpleName();
      throw new NominalSchemaMismatchException("Tuple of arity " + size, actual, path, node);
    }
    return t;
  }

  private static StvnValue.StvnString assertString(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnString s)) {
      throw new NominalSchemaMismatchException(":String", node.getClass().getSimpleName(), path, node);
    }
    return s;
  }

  private static StvnValue.StvnInteger assertInteger(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnInteger i)) {
      throw new NominalSchemaMismatchException(":Int", node.getClass().getSimpleName(), path, node);
    }
    return i;
  }

  private static StvnValue.StvnEnum assertEnum(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnEnum e)) {
      throw new NominalSchemaMismatchException(":Enum", node.getClass().getSimpleName(), path, node);
    }
    return e;
  }

  private static StvnValue.StvnOption assertOption(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnOption o)) {
      throw new NominalSchemaMismatchException(":Option", node.getClass().getSimpleName(), path, node);
    }
    return o;
  }

  private static StvnValue.StvnSeq assertSeq(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnSeq s)) {
      throw new NominalSchemaMismatchException(":Seq", node.getClass().getSimpleName(), path, node);
    }
    return s;
  }

  private static StvnValue.StvnBoolean assertBoolean(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnBoolean b)) {
      throw new NominalSchemaMismatchException(":Boolean", node.getClass().getSimpleName(), path, node);
    }
    return b;
  }

  private static String extractDefsBlock(String schemaContent) {
    int defsIdx = schemaContent.indexOf(":defs");
    if (defsIdx == -1) {
      throw new IllegalArgumentException("Schema does not contain :defs block: " + schemaContent);
    }
    int openBrace = schemaContent.indexOf('{', defsIdx);
    if (openBrace == -1) {
      throw new IllegalArgumentException("Invalid :defs structure in schema");
    }
    int closeBrace = findMatchingBrace(schemaContent, openBrace);
    return "  " + schemaContent.substring(defsIdx, closeBrace + 1);
  }

  private static int findMatchingBrace(String text, int openIndex) {
    int depth = 0;
    for (int i = openIndex; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return text.length() - 1;
  }

  /**
   * Sanitizes strings against quotes, backslashes, and control characters.
   *
   * @param s raw input string
   * @return escaped string safe for STVN string literals
   */
  public static String escape(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Unescapes sanitized STVN string literal tokens back to original string values.
   *
   * @param s escaped STVN string token
   * @return unescaped string
   */
  public static String unescape(String s) {
    if (s == null || s.indexOf('\\') == -1) return s != null ? s : "";
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(i + 1);
        switch (next) {
          case '\\' -> { sb.append('\\'); i++; }
          case '"' -> { sb.append('"'); i++; }
          case 'n' -> { sb.append('\n'); i++; }
          case 'r' -> { sb.append('\r'); i++; }
          case 't' -> { sb.append('\t'); i++; }
          default -> sb.append(c);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String stripHash(String s) {
    if (s == null) return "";
    return s.startsWith("#") ? s.substring(1) : s;
  }
}