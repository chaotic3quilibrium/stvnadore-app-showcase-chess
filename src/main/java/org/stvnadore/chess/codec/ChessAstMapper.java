package org.stvnadore.chess.codec;

import org.jspecify.annotations.Nullable;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.mapper.StvnMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Lossless bidirectional mapper between {@link GameHistory} domain records and pure-IR {@link StvnValue} trees.
 */
public final class ChessAstMapper {

  private static final String GAME_HISTORY_FQNI =
      StvnVocabulary.SIGIL_TYPIC + "gaming/chess/GameHistory";

  private ChessAstMapper() {
    // Utility class
  }

  /**
   * Serializes a {@link GameHistory} record into a canonical, self-contained STVN 2.0.0 document string.
   *
   * @param game the game history to format
   * @param schemaContent raw schema definition containing {@code :defs}
   * @return complete canonical STVN document text
   */
  public static String toStvnDocument(GameHistory game, String schemaContent) {
    Objects.requireNonNull(game, "game must not be null");
    Objects.requireNonNull(schemaContent, "schemaContent must not be null");

    StvnDocument schemaDoc = compileSchemaDocument(schemaContent);
    StvnSchema gameHistorySchema = resolveGameHistorySchema(schemaDoc.defs());
    StvnValue rootValue = toStvnValue(game, gameHistorySchema);
    StvnDocument payloadDoc = new StvnDocument(
        Optional.empty(),
        schemaDoc.defs(),
        Optional.of(gameHistorySchema),
        Optional.of(rootValue),
        true,
        false
    );
    return StvnCompiler.toCanonicalString(payloadDoc);
  }

  /**
   * Maps a {@link GameHistory} record directly into a validated pure-IR {@link StvnValue} tree.
   *
   * @param game the game history to format
   * @param schemaContent raw schema definition containing {@code :defs}
   * @return pure-IR {@link StvnValue} root node
   */
  public static StvnValue toStvnAst(GameHistory game, String schemaContent) {
    Objects.requireNonNull(game, "game must not be null");
    Objects.requireNonNull(schemaContent, "schemaContent must not be null");

    StvnDocument schemaDoc = compileSchemaDocument(schemaContent);
    return toStvnValue(game, schemaDoc.defs());
  }

  /**
   * Maps a {@link GameHistory} record directly into a pure-IR {@link StvnValue} using a pre-compiled catalog.
   *
   * @param game the game history domain record
   * @param defsCatalog pre-compiled definitions catalog containing {@code :gaming/chess/GameHistory}
   * @return pure-IR {@link StvnValue} root node
   */
  public static StvnValue toStvnValue(GameHistory game, StvnDefsCatalog defsCatalog) {
    Objects.requireNonNull(game, "game must not be null");
    Objects.requireNonNull(defsCatalog, "defsCatalog must not be null");

    StvnSchema gameHistorySchema = resolveGameHistorySchema(defsCatalog);
    return toStvnValue(game, gameHistorySchema);
  }

  static StvnValue toStvnValue(GameHistory game, StvnSchema gameHistorySchema) {
    return StvnMapper.toValue(game, Optional.of(gameHistorySchema))
        .orElseThrow(() -> new IllegalStateException("Failed to map GameHistory into pure-IR StvnValue"));
  }

  /**
   * Decodes an unpacked pure-IR {@link StvnValue} back into an immutable {@link GameHistory} domain record.
   *
   * @param ast unpacked root {@link StvnValue} node
   * @return domain {@link GameHistory} instance
   */
  public static GameHistory fromStvnAst(StvnValue ast) {
    Objects.requireNonNull(ast, "ast must not be null");
    assertNominalSchema(ast, "GameHistory", "root");
    StvnValue.StvnTuple rootTuple = assertTuple(ast, 5, "root");

    String gameId = assertString(rootTuple.elements().get(0), "root.gameId").value();
    String whitePlayer = assertString(rootTuple.elements().get(1), "root.whitePlayer").value();
    String blackPlayer = assertString(rootTuple.elements().get(2), "root.blackPlayer").value();

    StvnValue.StvnSeq turnsSeq = assertSeq(rootTuple.elements().get(3), "root.turns");
    List<TurnState> turns = new ArrayList<>();

    for (int i = 0; i < turnsSeq.elements().size(); i++) {
      StvnValue turnVal = turnsSeq.elements().get(i);
      String turnPath = "turns[" + i + "]";
      assertNominalSchema(turnVal, "TurnState", turnPath);
      StvnValue.StvnTuple turnTuple = assertTuple(turnVal, 5, turnPath);

      long turnNumber =
          assertInt(turnTuple.elements().get(0), turnPath + ".turnNumber").value().longValueExact();
      String colorStr = stripHash(assertEnum(turnTuple.elements().get(1), turnPath + ".color").variantTag());
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
        return Move.PromotionRole.valueOf(stripHash(promoEnum.variantTag()).toUpperCase(Locale.ROOT));
      });

      boolean isCapture = assertBoolean(moveTuple.elements().get(3), movePath + ".isCapture").value();
      int halfmoves =
          assertInt(moveTuple.elements().get(4), movePath + ".halfmoves").value().intValueExact();

      Move move = new Move(from, to, promo, isCapture, halfmoves);
      String fen = assertString(turnTuple.elements().get(3), turnPath + ".fen").value();
      int turnEval = assertInt(turnTuple.elements().get(4), turnPath + ".eval").value().intValueExact();

      turns.add(new TurnState(turnNumber, activeColor, move, fen, turnEval));
    }

    StvnValue.StvnOption resultOpt = assertOption(rootTuple.elements().get(4), "root.result");
    Optional<GameHistory.TerminalOutcome> result = resultOpt.value().map(v -> {
      StvnValue.StvnEnum resultEnum = assertEnum(v, "root.result.outcome");
      return GameHistory.TerminalOutcome.valueOf(stripHash(resultEnum.variantTag()).toUpperCase(Locale.ROOT));
    });

    return new GameHistory(gameId, whitePlayer, blackPlayer, turns, result);
  }

  private static Square mapSquare(StvnValue squareVal, String path) {
    assertNominalSchema(squareVal, "Square", path);
    StvnValue.StvnTuple squareTuple = assertTuple(squareVal, 2, path);
    String fileStr = stripHash(assertEnum(squareTuple.elements().get(0), path + ".file").variantTag());
    Square.File file = Square.File.valueOf(fileStr.toUpperCase(Locale.ROOT));
    int rank = assertInt(squareTuple.elements().get(1), path + ".rank").value().intValueExact();
    return new Square(file, rank);
  }

  static StvnDocument compileSchemaDocument(String schemaContent) {
    StvnCompilationResult<StvnDocument> compiled =
        StvnCompiler.compile(schemaContent, null, StvnParserConfig.STRICT);
    if (compiled.hasErrors() || compiled.document().isEmpty()) {
      String reason = compiled.diagnostics().isEmpty()
          ? "No StvnDocument produced"
          : compiled.diagnostics().getFirst().message();
      throw new IllegalArgumentException("Failed to compile chess schema definition: " + reason);
    }
    return compiled.document().get();
  }

  static StvnSchema resolveGameHistorySchema(StvnDefsCatalog defsCatalog) {
    return defsCatalog.findType(GAME_HISTORY_FQNI)
        .or(() -> defsCatalog.findType(StvnVocabulary.SIGIL_TYPIC + "GameHistory"))
        .orElseThrow(() -> new IllegalArgumentException(
            "Schema catalog does not define " + GAME_HISTORY_FQNI));
  }

  private static void assertNominalSchema(StvnValue node, String expectedSuffix, String path) {
    @Nullable String alias = node.schema().aliasName().orElse(null);
    if (alias == null
        || (!alias.equals(StvnVocabulary.SIGIL_TYPIC + expectedSuffix)
            && !alias.endsWith("/" + expectedSuffix))) {
      throw new NominalSchemaMismatchException(StvnVocabulary.SIGIL_TYPIC + expectedSuffix, alias, path, node);
    }
  }

  private static StvnValue.StvnTuple assertTuple(StvnValue node, int size, String path) {
    if (!(node instanceof StvnValue.StvnTuple t) || t.elements().size() != size) {
      String actual = node instanceof StvnValue.StvnTuple t2
          ? "Tuple of arity " + t2.elements().size()
          : node.getClass().getSimpleName();
      throw new NominalSchemaMismatchException("Tuple of arity " + size, actual, path, node);
    }
    return t;
  }

  private static StvnValue.StvnString assertString(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnString s)) {
      throw new NominalSchemaMismatchException(StvnVocabulary.TYPE_STRING, node.getClass().getSimpleName(), path, node);
    }
    return s;
  }

  private static StvnValue.StvnInt assertInt(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnInt i)) {
      throw new NominalSchemaMismatchException(StvnVocabulary.TYPE_INT, node.getClass().getSimpleName(), path, node);
    }
    return i;
  }

  private static StvnValue.StvnEnum assertEnum(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnEnum e)) {
      throw new NominalSchemaMismatchException(StvnVocabulary.TYPE_ENUM, node.getClass().getSimpleName(), path, node);
    }
    return e;
  }

  private static StvnValue.StvnOption assertOption(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnOption o)) {
      throw new NominalSchemaMismatchException(StvnVocabulary.TYPE_OPTION, node.getClass().getSimpleName(), path, node);
    }
    return o;
  }

  private static StvnValue.StvnSeq assertSeq(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnSeq s)) {
      throw new NominalSchemaMismatchException(StvnVocabulary.TYPE_SEQ, node.getClass().getSimpleName(), path, node);
    }
    return s;
  }

  private static StvnValue.StvnBoolean assertBoolean(StvnValue node, String path) {
    if (!(node instanceof StvnValue.StvnBoolean b)) {
      throw new NominalSchemaMismatchException(StvnVocabulary.TYPE_BOOLEAN, node.getClass().getSimpleName(), path, node);
    }
    return b;
  }

  private static String stripHash(@Nullable String s) {
    if (s == null) {
      return "";
    }
    return s.startsWith(StvnVocabulary.SIGIL_VALUE) ? s.substring(1) : s;
  }
}
