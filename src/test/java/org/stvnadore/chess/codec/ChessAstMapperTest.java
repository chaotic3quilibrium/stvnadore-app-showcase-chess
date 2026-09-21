package org.stvnadore.chess.codec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ChessAstMapperTest {

  private static String schemaContent;

  @BeforeAll
  static void loadSchema() throws Exception {
    try (InputStream is = ChessAstMapperTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is);
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("Lossless bi-directional AST mapping for all PromotionRole variants")
  void testPromotionRoleVariants() {
    for (Move.PromotionRole role : Move.PromotionRole.values()) {
      Move promoMove = new Move(Square.fromAlgebraic("e7"), Square.fromAlgebraic("e8"), Optional.of(role), false, 0);
      TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, promoMove, "fen-promo", 800);
      GameHistory original = new GameHistory("game-promo-" + role, "P1", "P2", List.of(turn), Optional.empty());

      StvnValue ast = ChessAstMapper.toStvnAst(original, schemaContent);
      assertNotNull(ast);

      GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);
      assertEquals(original, reconstructed);
      assertEquals(role, reconstructed.turns().get(0).move().promotion().orElseThrow());
    }
  }

  @Test
  @DisplayName("Lossless bi-directional AST mapping for all TerminalOutcome variants")
  void testTerminalOutcomeVariants() {
    for (GameHistory.TerminalOutcome outcome : GameHistory.TerminalOutcome.values()) {
      Move m = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
      TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, m, "fen", 0);
      GameHistory original = new GameHistory("game-outcome-" + outcome, "W", "B", List.of(turn), Optional.of(outcome));

      StvnValue ast = ChessAstMapper.toStvnAst(original, schemaContent);
      GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);
      assertEquals(original, reconstructed);
      assertEquals(outcome, reconstructed.result().orElseThrow());
    }
  }

  @Test
  @DisplayName("Numeric bounds AST mapping for Halfmoves (0..100) and TurnNumber (1..1023)")
  void testNumericBoundsMapping() {
    Move m0 = new Move(Square.fromAlgebraic("a2"), Square.fromAlgebraic("a3"), Optional.empty(), false, 0);
    TurnState turn1 = new TurnState(1, Piece.PieceColor.WHITE, m0, "fen1", -32768);

    Move m100 = new Move(Square.fromAlgebraic("a7"), Square.fromAlgebraic("a6"), Optional.empty(), false, 100);
    TurnState turn1023 = new TurnState(1023, Piece.PieceColor.BLACK, m100, "fen2", 32767);

    GameHistory original = new GameHistory("game-bounds", "Player \"A\"", "Player \\B\\", List.of(turn1, turn1023), Optional.empty());

    String doc = ChessAstMapper.toStvnDocument(original, schemaContent);
    assertTrue(doc.contains("1023"));
    assertTrue(doc.contains("100"));

    StvnValue ast = StvnCompiler.compile(doc).orElseThrow();
    GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);

    assertEquals(original, reconstructed);
    assertEquals(-32768, reconstructed.turns().get(0).evaluationCentipawns());
    assertEquals(32767, reconstructed.turns().get(1).evaluationCentipawns());
  }

  @Test
  @DisplayName("Special character and string escaping round-trip")
  void testStringEscapingRoundTrip() {
    String trickyName = "Grandmaster \"The Tactician\"\nSpecial\tPlayer\\Expert";
    Move m = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    TurnState turn = new TurnState(1, Piece.PieceColor.WHITE, m, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", 20);

    GameHistory original = new GameHistory("match/2026/08#1", trickyName, "Standard Opponent", List.of(turn), Optional.empty());
    StvnValue ast = ChessAstMapper.toStvnAst(original, schemaContent);
    GameHistory reconstructed = ChessAstMapper.fromStvnAst(ast);

    assertEquals(original, reconstructed);
    assertEquals(trickyName, reconstructed.whitePlayer());
  }

  @Test
  @DisplayName("Passing transposed nominal types (e.g., :Piece where :Square is expected) throws NominalSchemaMismatchException")
  void testTransposedNominalTypeThrowsException() {
    String transposedSchema = """
        {
          :defs {
            :package :org/stvnadore/chess {
              :File             :Enum [ #A #B #C #D #E #F #G #H ]
              :Rank             { #unsigned #size 4 #minIncl 1 #maxExcl 9 } :Int
              :Square           :Tuple( :File :Rank )
              :Color            :Enum [ #WHITE #BLACK ]
              :PieceRole        :Enum [ #PAWN #KNIGHT #BISHOP #ROOK #QUEEN #KING ]
              :Piece            :Tuple( :Color :PieceRole )
              :PromotionRole    { #filterExcl [ #PAWN #KING ] } :PieceRole
              :PromotionOption  :Option( :PromotionRole )
              :IsCapture        :Boolean
              :HalfmovesSincePawnOrCapture { #unsigned #size 7 #minIncl 0 #maxExcl 101 } :Int
              :SanMoveString    { #minSize 1 #maxSize 8 } :String
              :Move             :Tuple( :Piece :Square :PromotionOption :IsCapture :HalfmovesSincePawnOrCapture )
              :TurnNumber       { #unsigned #size 10 #minIncl 1 } :Int
              :FenStringFixed   { #minSize 1 #maxSize 128 } :String
              :ForsythEdwardsNotation :FenStringFixed
              :CentipawnEvaluation { #size 16 } :Int
              :TurnState        :Tuple( :TurnNumber :Color :Move :ForsythEdwardsNotation :CentipawnEvaluation )
              :MatchId          { #minSize 1 #maxSize 64 } :String
              :PlayerName       { #minSize 1 #maxSize 64 } :String
              :WhitePlayer      :PlayerName
              :BlackPlayer      :PlayerName
              :TerminalOutcome  :Enum [ #WHITE_WIN #BLACK_WIN #DRAW ]
              :MatchResult      :Option( :TerminalOutcome )
              :GameHistory      :Tuple( :MatchId :WhitePlayer :BlackPlayer :Seq( :TurnState ) :MatchResult )
            }
            :use [ :org/stvnadore/chess { #strip } ]
          }
          :type :GameHistory
          :body (
            "transposed-test"
            "White"
            "Black"
            [
              ( 1 #WHITE ( ( #WHITE #PAWN ) ( #E 4 ) #None #FALSE 0 ) "fen" 0 )
            ]
            #None
          )
        }
        """;

    StvnValue transposedAst = StvnCompiler.compile(transposedSchema)
        .orElseThrow(() -> new IllegalStateException("Failed to compile transposed test document"));

    NominalSchemaMismatchException ex = assertThrows(
        NominalSchemaMismatchException.class,
        () -> ChessAstMapper.fromStvnAst(transposedAst)
    );

    assertEquals(":Square", ex.expectedAlias());
    assertTrue(ex.actualAlias().endsWith("Piece"), () -> "Actual alias should end with Piece: " + ex.actualAlias());
    assertEquals("turns[0].move.from", ex.path());
    assertNotNull(ex.offendingNode());
  }

  @Test
  @DisplayName("Root AST lacking :GameHistory nominal alias throws NominalSchemaMismatchException at path 'root'")
  void testRootNominalMismatchThrowsException() {
    String invalidRootDoc = """
        {
          :defs {
            :UserAccount :Tuple( :String :String :String :Seq( :Int ) :Option( :Boolean ) )
          }
          :type :UserAccount
          :body ( "id" "user1" "user2" [] #None )
        }
        """;

    StvnValue invalidRootAst = StvnCompiler.compile(invalidRootDoc)
        .orElseThrow(() -> new IllegalStateException("Failed to compile invalid root document"));

    NominalSchemaMismatchException ex = assertThrows(
        NominalSchemaMismatchException.class,
        () -> ChessAstMapper.fromStvnAst(invalidRootAst)
    );

    assertEquals(":GameHistory", ex.expectedAlias());
    assertTrue(ex.actualAlias().endsWith("UserAccount"));
    assertEquals("root", ex.path());
  }
}
