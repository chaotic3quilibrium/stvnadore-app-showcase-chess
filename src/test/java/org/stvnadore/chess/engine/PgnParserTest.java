package org.stvnadore.chess.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PgnParserTest {

  @Test
  @DisplayName("Parse canonical 1858 Opera Game (Paul Morphy vs Duke Karl / Count Isouard)")
  void testParseOperaGame() {
    String pgn = """
        [Event "Paris Opera"]
        [Site "Paris FRA"]
        [Date "1858.??.??"]
        [Round "?"]
        [White "Morphy, Paul"]
        [Black "Duke Karl / Count Isouard"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
        8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7
        14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0
        """;

    GameHistory game = PgnParser.parse(pgn);

    assertNotNull(game);
    assertEquals("Paris Opera", game.gameId());
    assertEquals("Morphy, Paul", game.whitePlayer());
    assertEquals("Duke Karl / Count Isouard", game.blackPlayer());
    assertEquals(33, game.turns().size());
    assertEquals(Optional.of(GameHistory.TerminalOutcome.WHITE_WIN), game.result());

    // Check last move Rd8#
    Move lastMove = game.turns().get(32).move();
    assertEquals(Square.fromAlgebraic("d1"), lastMove.from());
    assertEquals(Square.fromAlgebraic("d8"), lastMove.to());
  }

  @Test
  @DisplayName("Parse Kasparov vs Deep Blue (Game 6, 1997)")
  void testParseKasparovDeepBlueGame() {
    String pgn = """
        [Event "IBM Man-Machine, New York USA"]
        [Site "New York, NY USA"]
        [Date "1997.05.11"]
        [Round "6"]
        [White "Deep Blue"]
        [Black "Kasparov, Garry"]
        [Result "1-0"]

        1. e4 c6 2. d4 d5 3. Nc3 dxe4 4. Nxe4 Nd7 5. Ng5 Ngf6 6. Bd3 e6 7. N1f3 h6
        8. Nxe6 Qe7 9. O-O fxe6 10. Bg6+ Kd8 11. Bf4 b5 12. a4 Bb7 13. Re1 Nd5
        14. Bg3 Kc8 15. axb5 cxb5 16. Qd3 Bc6 17. Bf5 exf5 18. Rxe7 Bxe7 19. c4 1-0
        """;

    GameHistory game = PgnParser.parse(pgn);

    assertNotNull(game);
    assertEquals("Deep Blue", game.whitePlayer());
    assertEquals("Kasparov, Garry", game.blackPlayer());
    assertEquals(37, game.turns().size());
    assertEquals(Optional.of(GameHistory.TerminalOutcome.WHITE_WIN), game.result());
  }

  @Test
  @DisplayName("Parse PGN moves with file, rank, and dual disambiguation")
  void testSanDisambiguation() {
    // 1. File disambiguation (Nbd7 and N1f3 in Caro-Kann)
    String pgn = """
        [Event "Disambiguation Test"]
        [White "White"]
        [Black "Black"]
        [Result "*"]

        1. e4 c6 2. d4 d5 3. Nc3 dxe4 4. Nxe4 Nd7 5. Nf3 Ngf6 6. Ned2 *
        """;

    GameHistory game = PgnParser.parse(pgn);
    assertEquals(11, game.turns().size());

    // Check Nd7 (from b8 to d7)
    Move nd7 = game.turns().get(7).move();
    assertEquals(Square.fromAlgebraic("b8"), nd7.from());
    assertEquals(Square.fromAlgebraic("d7"), nd7.to());

    // Check Ned2 (from e4 to d2)
    Move ned2 = game.turns().get(10).move();
    assertEquals(Square.fromAlgebraic("e4"), ned2.from());
    assertEquals(Square.fromAlgebraic("d2"), ned2.to());
  }

  @Test
  @DisplayName("Parse PGN with castling variations (O-O, O-O-O, 0-0, 0-0-0)")
  void testCastlingVariants() {
    String pgn = """
        [Event "Castling Test"]
        [White "Player1"]
        [Black "Player2"]
        [Result "1/2-1/2"]

        1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. 0-0 Nf6 5. d3 0-0 1/2-1/2
        """;

    GameHistory game = PgnParser.parse(pgn);
    assertEquals(10, game.turns().size());
    assertEquals(Optional.of(GameHistory.TerminalOutcome.DRAW), game.result());

    Move whiteCastle = game.turns().get(6).move();
    assertEquals(Square.fromAlgebraic("e1"), whiteCastle.from());
    assertEquals(Square.fromAlgebraic("g1"), whiteCastle.to());

    Move blackCastle = game.turns().get(9).move();
    assertEquals(Square.fromAlgebraic("e8"), blackCastle.from());
    assertEquals(Square.fromAlgebraic("g8"), blackCastle.to());
  }

  @Test
  @DisplayName("Parse PGN with pawn promotion syntax")
  void testPawnPromotionSyntax() {
    String pgn = """
        [Event "Promotion Test"]
        [White "W"]
        [Black "B"]
        [Result "*"]

        1. e4 d5 2. exd5 c6 3. dxc6 bxc6 4. d4 e5 5. dxe5 Qxd1+ 6. Kxd1 Nd7
        7. e6 fxe6 8. Bc4 Nb6 9. Bb3 Bd6 10. Nf3 Nf6 11. Re1 O-O 12. Bxe6+ Bxe6
        13. Rxe6 Rad8 14. Ke1 Nbd5 15. Bg5 Nb4 16. Na3 Nbd5 17. Nc4 Bf4
        18. Bxf6 gxf6 19. Rxc6 Rfe8+ 20. Kf1 Nb4 21. Rxf6 Nxc2 22. Rb1 Bb8
        23. g3 Kg7 24. Rc6 Nb4 25. Rc5 Nd3 26. Rg5+ Kh6 27. Rg4 Rf8 28. Kg2 Rd5
        29. Rd4 Rxd4 30. Nxd4 Rxf2+ 31. Kh3 Kg5 32. Rd1 Ne5 33. Nxe5 Bxe5
        34. Ne6+ Kf6 35. Nc5 Rxb2 36. Nd7+ Kf5 37. Nxe5 Kxe5 38. Rd7 Rxa2
        39. Rxh7 a5 40. Ra7 a4 41. g4 a3 42. g5 Kf5 43. Ra5+ Kg6 44. Kg3 Ra1
        45. h4 a2 46. Kg2 Kh5 47. Ra4 Kg6 48. Ra6+ Kh5 49. g6 Kh6 50. h5 Kg7
        51. Ra7+ Kh6 52. g7 Kh7 53. h6 Rb1 54. Rxa2 Rb8 55. Ra6 Rc8 56. Kg3 Rd8
        57. Kg4 Rc8 58. Kf5 Rd8 59. Re6 Rc8 60. Kf6 Rb8 61. Kf7 Rb7+ 62. Re7 Rb8
        63. Re8 Rxe8 64. Kxe8 Kg8 65. Ke7 Kh7 66. Kf7 Kxh6 67. g8=Q Kh5 68. Qg3 *
        """;

    GameHistory game = PgnParser.parse(pgn);
    assertNotNull(game);
    assertEquals(135, game.turns().size());

    // Turn 133 is 67. g8=Q
    TurnState promoTurn = game.turns().get(132);
    assertEquals(Square.fromAlgebraic("g7"), promoTurn.move().from());
    assertEquals(Square.fromAlgebraic("g8"), promoTurn.move().to());
    assertEquals(Optional.of(Move.PromotionRole.QUEEN), promoTurn.move().promotion());
  }

  @Test
  @DisplayName("Parse PGN containing comments, variations, annotations, and NAGs")
  void testCommentsAndNags() {
    String pgn = """
        [Event "Annotations Test"]
        [White "Alpha"]
        [Black "Beta"]
        [Result "1-0"]

        1. e4 {Best by test} 1... e5 $1 2. Nf3 (2. f4 exf4) 2... Nc6 3. Bc4! Bc5?! 4. c3 $2 Nf6 1-0
        """;

    GameHistory game = PgnParser.parse(pgn);
    assertEquals(8, game.turns().size());
    assertEquals(Optional.of(GameHistory.TerminalOutcome.WHITE_WIN), game.result());
  }

  @Test
  @DisplayName("Reject illegal move in PGN with IllegalMoveException")
  void testIllegalMoveRejection() {
    String illegalPgn = """
        [Event "Illegal Move Test"]
        [White "W"]
        [Black "B"]
        [Result "*"]

        1. e4 e5 2. Ke2 Ke7 3. Ke3 Ke6 4. Kd4 c5+ 5. Kxc5 d5 6. Kb5 Qb6# 7. Ka5 Qxa2 *
        """;

    assertThrows(IllegalMoveException.class, () -> PgnParser.parse(illegalPgn));
  }

  @Test
  @DisplayName("Reject malformed SAN token in PGN with PgnParseException")
  void testMalformedSanRejection() {
    String badPgn = """
        [Event "Bad SAN Test"]
        [White "W"]
        [Black "B"]
        [Result "*"]

        1. e4 ??? 2. Nf3 *
        """;

    assertThrows(PgnParseException.class, () -> PgnParser.parse(badPgn));
  }
}
