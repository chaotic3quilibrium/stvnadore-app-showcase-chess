package org.stvnadore.chess.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ChessEngineTacticsTest {

  private static ChessBinaryCodec codec;

  @BeforeAll
  static void setUpCodec() throws Exception {
    try (InputStream is = ChessEngineTacticsTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is, "Embedded schema must exist");
      String schema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      codec = new ChessBinaryCodec(schema);
    }
  }

  @Test
  @DisplayName("Opera Game (Morphy vs Duke of Brunswick, 1858) full replay and binary isomorphic round-trip")
  void testOperaGameReplayAndBinaryRoundTrip() {
    String[][] operaMoves = {
        {"e2", "e4"}, // 1. e4
        {"e7", "e5"}, // 1... e5
        {"g1", "f3"}, // 2. Nf3
        {"d7", "d6"}, // 2... d6
        {"d2", "d4"}, // 3. d4
        {"c8", "g4"}, // 3... Bg4
        {"d4", "e5"}, // 4. dxe5
        {"g4", "f3"}, // 4... Bxf3
        {"d1", "f3"}, // 5. Qxf3
        {"d6", "e5"}, // 5... dxe5
        {"f1", "c4"}, // 6. Bc4
        {"g8", "f6"}, // 6... Nf6
        {"f3", "b3"}, // 7. Qb3
        {"d8", "e7"}, // 7... Qe7
        {"b1", "c3"}, // 8. Nc3
        {"c7", "c6"}, // 8... c6
        {"c1", "g5"}, // 9. Bg5
        {"b7", "b5"}, // 9... b5
        {"c3", "b5"}, // 10. Nxb5
        {"c6", "b5"}, // 10... cxb5
        {"c4", "b5"}, // 11. Bxb5+
        {"b8", "d7"}, // 11... Nbd7
        {"e1", "c1"}, // 12. O-O-O (Queenside Castle!)
        {"a8", "d8"}, // 12... Rd8
        {"d1", "d7"}, // 13. Rxd7
        {"d8", "d7"}, // 13... Rxd7
        {"h1", "d1"}, // 14. Rd1
        {"e7", "e6"}, // 14... Qe6
        {"b5", "d7"}, // 15. Bxd7+
        {"f6", "d7"}, // 15... Nxd7
        {"b3", "b8"}, // 16. Qb8+! (Queen Sacrifice)
        {"d7", "b8"}, // 16... Nxb8
        {"d1", "d8"}  // 17. Rd8# (Checkmate!)
    };

    BoardState currentBoard = BoardState.initial();
    List<TurnState> turns = new ArrayList<>();
    long turnNumber = 1;

    for (int i = 0; i < operaMoves.length; i++) {
      Square from = Square.fromAlgebraic(operaMoves[i][0]);
      Square to = Square.fromAlgebraic(operaMoves[i][1]);
      Piece.PieceColor activeColor = currentBoard.activeColor();

      List<Move> legalMoves = MoveValidator.generateLegalMoves(currentBoard);
      final int step = i + 1;
      Move executedMove = legalMoves.stream()
          .filter(m -> m.from().equals(from) && m.to().equals(to))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "Illegal move at step " + step + " (" + activeColor + "): " + from.toAlgebraic() + " -> " + to.toAlgebraic()));

      BoardState nextBoard = MoveValidator.applyMove(currentBoard, executedMove);
      String fen = FenCodec.format(nextBoard);

      // Verify that FenCodec lossless round-trip works for every single intermediate ply
      BoardState reparsed = FenCodec.parse(fen);
      assertEquals(nextBoard, reparsed, "FEN round-trip failed at ply " + step);

      turns.add(new TurnState(turnNumber++, activeColor, executedMove, fen, 100));
      currentBoard = nextBoard;
    }

    // Assert final position is checkmate with WHITE_WIN
    List<Move> terminalLegalMoves = MoveValidator.generateLegalMoves(currentBoard);
    assertTrue(terminalLegalMoves.isEmpty(), "Black must have zero legal moves after Rd8#");
    assertTrue(MoveValidator.isInCheck(currentBoard, Piece.PieceColor.BLACK), "Black King must be in checkmate");

    Optional<GameHistory.TerminalOutcome> outcome = TerminalDetector.detectOutcome(currentBoard, terminalLegalMoves);
    assertTrue(outcome.isPresent());
    assertEquals(GameHistory.TerminalOutcome.WHITE_WIN, outcome.get());

    // Package into GameHistory
    GameHistory operaGame = new GameHistory(
        "opera-game-1858",
        "Paul Morphy",
        "Duke of Brunswick & Count Isouard",
        turns,
        outcome
    );

    assertEquals(33, operaGame.turns().size());

    // Encode to STVN Binary Strategy 0x07 and Decode
    ByteBuffer encoded = codec.encode(operaGame);
    assertNotNull(encoded);
    assertTrue(encoded.remaining() > 100);

    GameHistory decoded = codec.decode(encoded);
    assertEquals(operaGame, decoded, "Binary round-trip must be 100% isomorphic");
  }
}
