package org.stvnadore.chess.fuzz;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.codec.ChessBinaryCodec;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-level systematic mutation and fuzzing suite for STVN Strategy 0x07 binary payloads.
 * Sweeps every byte index from 0 to payload length and asserts fail-fast rejection boundaries.
 */
public class PayloadCorruptionFuzzTest {

  private static ChessBinaryCodec codec;
  private static String schemaContent;

  @BeforeAll
  static void setUp() throws Exception {
    try (InputStream is = PayloadCorruptionFuzzTest.class.getResourceAsStream("/schemas/chess_turn.stvn_inclf")) {
      assertNotNull(is, "Schema resource must exist");
      schemaContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      codec = new ChessBinaryCodec(schemaContent);
    }
  }

  @Test
  @DisplayName("Systematic byte sweep (0..N-1) across Strategy 0x07 payload asserts fail-fast error rejection or mutation detection on every offset")
  void testExhaustiveByteCorruptionSweepAcrossStrategy07Payload() {
    GameHistory representativeGame = buildRepresentativeGame();
    ByteBuffer validBuffer = codec.encode(representativeGame);
    byte[] validBytes = new byte[validBuffer.remaining()];
    validBuffer.get(validBytes);

    int totalBytes = validBytes.length;
    assertTrue(totalBytes > 37, "Strategy 0x07 payload must contain 37-byte header plus body");

    int headerPoisonCount = 0;
    int headerMalformedCount = 0;
    int bodyExceptionCount = 0;
    int bodyMutationDetectedCount = 0;
    int paddingByteCount = 0;

    for (int offset = 0; offset < totalBytes; offset++) {
      byte[] corrupted = validBytes.clone();
      // Invert all bits at the target offset
      corrupted[offset] = (byte) (corrupted[offset] ^ 0xFF);

      ByteBuffer corruptedBuffer = ByteBuffer.wrap(corrupted);

      if (offset >= 5 && offset <= 36) {
        // Offsets 5..36 contain the 32-byte SHA-256 schema CAS digest
        assertThrows(
            PoisonedRegistryPayloadException.class,
            () -> codec.decode(corruptedBuffer),
            "Mutated SHA-256 header digest at byte offset " + offset + " must throw PoisonedRegistryPayloadException"
        );
        headerPoisonCount++;
      } else if (offset >= 0 && offset <= 4) {
        // Offsets 0..3 (Magic 'STVN') and offset 4 (Strategy 0x07 byte)
        assertThrows(
            Exception.class,
            () -> codec.decode(corruptedBuffer),
            "Mutated header magic/control byte at offset " + offset + " must fail fast"
        );
        headerMalformedCount++;
      } else {
        // Offsets 37..N-1 (body payload)
        try {
          GameHistory decoded = codec.decode(corruptedBuffer);
          if (representativeGame.equals(decoded)) {
            // Buffer alignment / trailing padding byte
            paddingByteCount++;
          } else {
            bodyMutationDetectedCount++;
          }
        } catch (Exception e) {
          bodyExceptionCount++;
        }
      }
    }

    assertEquals(32, headerPoisonCount, "Exactly 32 SHA-256 digest bytes must trigger PoisonedRegistryPayloadException");
    assertEquals(5, headerMalformedCount, "All 5 magic and strategy header bytes must trigger fail-fast exception");
    assertTrue(bodyExceptionCount > 0, "Structural body corruptions must trigger decoding exceptions");
    assertTrue(bodyMutationDetectedCount > 0, "Value body corruptions must alter decoded state");
    assertTrue(paddingByteCount <= 7, "Trailing alignment padding bytes must not exceed 7 bytes");
    assertEquals(
        totalBytes - 37,
        bodyExceptionCount + bodyMutationDetectedCount + paddingByteCount,
        "All body bytes must be accounted for across exceptions, value mutations, and alignment padding"
    );
  }

  @Test
  @DisplayName("All 32 SHA-256 header digest bytes (offsets 5..36) strictly throw PoisonedRegistryPayloadException")
  void testHeaderSha256EveryByteTamperRejection() {
    GameHistory game = buildRepresentativeGame();
    ByteBuffer validBuffer = codec.encode(game);
    byte[] validBytes = new byte[validBuffer.remaining()];
    validBuffer.get(validBytes);

    for (int offset = 5; offset <= 36; offset++) {
      byte[] tampered = validBytes.clone();
      tampered[offset] ^= 0x01; // Single bit mutation

      assertThrows(
          PoisonedRegistryPayloadException.class,
          () -> codec.decode(ByteBuffer.wrap(tampered)),
          "Bit flip in SHA-256 digest at offset " + offset + " must raise PoisonedRegistryPayloadException"
      );
    }
  }

  @Test
  @DisplayName("Payload truncation at every byte boundary (0..N-1) throws fail-fast exception")
  void testPayloadTruncationRejection() {
    GameHistory game = buildRepresentativeGame();
    ByteBuffer validBuffer = codec.encode(game);
    byte[] validBytes = new byte[validBuffer.remaining()];
    validBuffer.get(validBytes);

    for (int length = 0; length < validBytes.length; length++) {
      byte[] truncated = new byte[length];
      System.arraycopy(validBytes, 0, truncated, 0, length);

      assertThrows(
          Exception.class,
          () -> codec.decode(ByteBuffer.wrap(truncated)),
          "Truncated payload of length " + length + " must be rejected"
      );
    }
  }

  private static GameHistory buildRepresentativeGame() {
    List<TurnState> turns = new ArrayList<>();

    // Turn 1: 1. e4
    Move m1 = new Move(Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"), Optional.empty(), false, 0);
    turns.add(new TurnState(1, Piece.PieceColor.WHITE, m1, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", 20));

    // Turn 2: 1... e5
    Move m2 = new Move(Square.fromAlgebraic("e7"), Square.fromAlgebraic("e5"), Optional.empty(), false, 0);
    turns.add(new TurnState(2, Piece.PieceColor.BLACK, m2, "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2", 15));

    // Turn 3: 2. Nf3
    Move m3 = new Move(Square.fromAlgebraic("g1"), Square.fromAlgebraic("f3"), Optional.empty(), false, 1);
    turns.add(new TurnState(3, Piece.PieceColor.WHITE, m3, "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", 35));

    // Turn 4: 2... Nc6
    Move m4 = new Move(Square.fromAlgebraic("b8"), Square.fromAlgebraic("c6"), Optional.empty(), false, 2);
    turns.add(new TurnState(4, Piece.PieceColor.BLACK, m4, "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", 30));

    // Turn 5: 3. Bc4
    Move m5 = new Move(Square.fromAlgebraic("f1"), Square.fromAlgebraic("c4"), Optional.empty(), false, 3);
    turns.add(new TurnState(5, Piece.PieceColor.WHITE, m5, "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3", 45));

    return new GameHistory(
        "fuzz-rep-game-001",
        "Grandmaster-A",
        "Grandmaster-B",
        turns,
        Optional.of(GameHistory.TerminalOutcome.WHITE_WIN)
    );
  }
}
