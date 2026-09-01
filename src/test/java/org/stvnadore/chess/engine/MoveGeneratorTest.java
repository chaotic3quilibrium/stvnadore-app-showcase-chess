package org.stvnadore.chess.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Square;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MoveGeneratorTest {

  @Test
  @DisplayName("Initial position generates exactly 20 pseudo-legal moves for White")
  void testInitialPositionMoveCount() {
    BoardState initial = BoardState.initial();
    List<Move> moves = MoveGenerator.generatePseudoLegalMoves(initial);

    // 16 pawn moves (8 single pushes + 8 double pushes) + 4 knight moves (b1-a3, b1-c3, g1-f3, g1-h3)
    assertEquals(20, moves.size());
  }

  @Test
  @DisplayName("Knight leaping geometry generates 8 moves from center and 2 from corner")
  void testKnightMoveGeneration() {
    // Lone white knight at d4
    BoardState centerKnight = FenCodec.parse("8/8/8/8/3N4/8/8/4K2k w - - 0 1");
    List<Move> centerMoves = MoveGenerator.generatePseudoLegalMoves(centerKnight);
    // 8 knight moves + king moves
    long knightMoves = centerMoves.stream()
        .filter(m -> m.from().equals(Square.fromAlgebraic("d4")))
        .count();
    assertEquals(8, knightMoves);

    // Lone white knight at a1
    BoardState cornerKnight = FenCodec.parse("8/8/8/8/8/8/8/N3K2k w - - 0 1");
    List<Move> cornerMoves = MoveGenerator.generatePseudoLegalMoves(cornerKnight);
    long cornerKnightMoves = cornerMoves.stream()
        .filter(m -> m.from().equals(Square.fromAlgebraic("a1")))
        .count();
    assertEquals(2, cornerKnightMoves); // b3 and c2
  }

  @Test
  @DisplayName("Sliding pieces respect board edges and obstacle termination")
  void testSlidingRayGeneration() {
    // White rook on d4 with friendly pawn on d6 and opponent pawn on d2
    BoardState rookObstacle = FenCodec.parse("8/8/3P4/8/3R4/8/3p4/4K2k w - - 0 1");
    List<Move> moves = MoveGenerator.generatePseudoLegalMoves(rookObstacle);

    List<Move> rookMoves = moves.stream()
        .filter(m -> m.from().equals(Square.fromAlgebraic("d4")))
        .toList();

    // Horizontal: a4, b4, c4, e4, f4, g4, h4 (7 squares)
    // North: d5 (1 square, blocked before d6)
    // South: d3, d2 (capture opponent d2, blocked past d2) (2 squares)
    // Total = 10 moves
    assertEquals(10, rookMoves.size());
    assertTrue(rookMoves.stream().anyMatch(m -> m.to().equals(Square.fromAlgebraic("d2")) && m.isCapture()));
    assertFalse(rookMoves.stream().anyMatch(m -> m.to().equals(Square.fromAlgebraic("d6"))));
  }

  @Test
  @DisplayName("Pawn reaching 8th rank expands into 4 distinct promotion roles")
  void testPawnPromotionMultiplicity() {
    // White pawn on e7, empty e8
    BoardState promoBoard = FenCodec.parse("8/4P3/8/8/8/8/8/4K2k w - - 0 1");
    List<Move> moves = MoveGenerator.generatePseudoLegalMoves(promoBoard);

    List<Move> pawnPromoMoves = moves.stream()
        .filter(m -> m.from().equals(Square.fromAlgebraic("e7")) && m.to().equals(Square.fromAlgebraic("e8")))
        .toList();

    assertEquals(4, pawnPromoMoves.size());
    assertTrue(pawnPromoMoves.stream().anyMatch(m -> m.promotion().orElseThrow() == Move.PromotionRole.QUEEN));
    assertTrue(pawnPromoMoves.stream().anyMatch(m -> m.promotion().orElseThrow() == Move.PromotionRole.ROOK));
    assertTrue(pawnPromoMoves.stream().anyMatch(m -> m.promotion().orElseThrow() == Move.PromotionRole.BISHOP));
    assertTrue(pawnPromoMoves.stream().anyMatch(m -> m.promotion().orElseThrow() == Move.PromotionRole.KNIGHT));
  }

  @Test
  @DisplayName("En passant candidate move is generated when enPassantTarget is active")
  void testEnPassantGeneration() {
    // White pawn on e5, Black pawn just pushed d7-d5, enPassantTarget is d6
    BoardState epBoard = FenCodec.parse("8/8/8/3pP3/8/8/8/4K2k w - d6 0 1");
    List<Move> moves = MoveGenerator.generatePseudoLegalMoves(epBoard);

    List<Move> epMoves = moves.stream()
        .filter(m -> m.from().equals(Square.fromAlgebraic("e5")) && m.to().equals(Square.fromAlgebraic("d6")))
        .toList();

    assertEquals(1, epMoves.size());
    assertTrue(epMoves.getFirst().isCapture());
  }
}
