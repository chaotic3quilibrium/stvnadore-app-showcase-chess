package org.stvnadore.chess.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.chess.util.SystemErrCapture;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ChessCliApplicationTest {

  @Test
  @DisplayName("Valid CLI commands execute successfully with exit code 0")
  void testSuccessfulLifecycleCommands() throws Exception {
    Path tempBinary = Files.createTempFile("chess_sim_", ".stvn_bin");
    Path tempPgn = Files.createTempFile("opera_", ".pgn");
    Path tempPgnOutput = Files.createTempFile("opera_out_", ".stvn_bin");

    try {
      // 1. Execute simulate -> Exit Code 0
      int simCode = ChessCliApplication.execute(new String[]{"simulate", tempBinary.toString()});
      assertEquals(0, simCode);
      assertTrue(Files.exists(tempBinary));
      assertTrue(Files.size(tempBinary) > 0);

      // 2. Execute verify -> Exit Code 0
      int verifyCode = ChessCliApplication.execute(new String[]{"verify", tempBinary.toString()});
      assertEquals(0, verifyCode);

      // 3. Execute replay (auto mode with delay 0) -> Exit Code 0
      int replayCode = ChessCliApplication.execute(new String[]{"replay", tempBinary.toString(), "--delay", "0", "--ascii"});
      assertEquals(0, replayCode);

      // 4. Execute pgn-import -> Exit Code 0
      String operaPgnContent = """
          [Event "Paris Opera"]
          [White "Paul Morphy"]
          [Black "Duke Karl"]
          [Result "1-0"]

          1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
          8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7
          14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0
          """;
      Files.writeString(tempPgn, operaPgnContent);

      int pgnCode = ChessCliApplication.execute(new String[]{"pgn-import", tempPgn.toString(), tempPgnOutput.toString()});
      assertEquals(0, pgnCode);
      assertTrue(Files.exists(tempPgnOutput));
      assertTrue(Files.size(tempPgnOutput) > 0);

      // Verify the generated PGN binary can be replayed
      int replayPgnCode = ChessCliApplication.execute(new String[]{"replay", tempPgnOutput.toString(), "--delay", "0"});
      assertEquals(0, replayPgnCode);

      // 5. Execute poison command -> Exit Code 0 (handled simulation test)
      int poisonCode = ChessCliApplication.execute(new String[]{"poison", tempBinary.toString()});
      assertEquals(0, poisonCode);

      // 6. Execute benchmark command -> Exit Code 0
      int benchCode = ChessCliApplication.execute(new String[]{"benchmark"});
      assertEquals(0, benchCode);

    } finally {
      Files.deleteIfExists(tempBinary);
      Files.deleteIfExists(tempPgn);
      Files.deleteIfExists(tempPgnOutput);
    }
  }

  @Test
  @DisplayName("Unrecognized CLI command emits error to System.err and returns exit code 1")
  void testUnknownCommandRejection() {
    try (SystemErrCapture capture = SystemErrCapture.mute()) {
      int exitCode = ChessCliApplication.execute(new String[]{"unknown_cmd"});
      assertEquals(1, exitCode);
      capture.assertContains("Unknown command: unknown_cmd");
    }
  }

  @Test
  @DisplayName("Verification of non-existent binary file emits error to System.err and returns exit code 1")
  void testMissingFileRejection() {
    try (SystemErrCapture capture = SystemErrCapture.mute()) {
      int exitCode = ChessCliApplication.execute(new String[]{"verify", "non_existent_file.stvn_bin"});
      assertEquals(1, exitCode);
      capture.assertContains("File not found:");
      capture.assertContains("Error executing command 'verify':");
    }
  }

  @Test
  @DisplayName("Replaying tampered zero-trust payload emits security alert to System.err and returns exit code 2")
  void testPoisonedPayloadRejection() throws Exception {
    Path tempBinary = Files.createTempFile("poison_source_", ".stvn_bin");
    Path tempPoisonFile = Files.createTempFile("poison_target_", ".stvn_bin");

    try {
      // Generate a valid binary first
      assertEquals(0, ChessCliApplication.execute(new String[]{"simulate", tempBinary.toString()}));

      // Tamper with header SHA-256 digest
      byte[] validBytes = Files.readAllBytes(tempBinary);
      byte[] poisonedBytes = org.stvnadore.chess.codec.ChessBinaryCodec.poisonPayload(validBytes);
      Files.write(tempPoisonFile, poisonedBytes);

      try (SystemErrCapture capture = SystemErrCapture.mute()) {
        int exitCode = ChessCliApplication.execute(new String[]{"replay", tempPoisonFile.toString()});
        assertEquals(2, exitCode);
        capture.assertContains("SECURITY ALERT: Poisoned registry payload detected!");
        capture.assertContains("Schema hash mismatch!");
      }
    } finally {
      Files.deleteIfExists(tempBinary);
      Files.deleteIfExists(tempPoisonFile);
    }
  }

  @Test
  @DisplayName("Importing PGN with illegal moves emits engine error to System.err and returns exit code 3")
  void testIllegalPgnMoveRejection() throws Exception {
    Path tempIllegalPgn = Files.createTempFile("illegal_", ".pgn");
    try {
      String illegalPgnContent = """
          [Event "Illegal Move"]
          [White "W"]
          [Black "B"]
          [Result "*"]

          1. e4 e5 2. Ke2 Ke7 3. Ke3 Ke6 4. Kd4 c5+ 5. Kxc5 d5 6. Kb5 Qb6# 7. Ka5 Qxa2 *
          """;
      Files.writeString(tempIllegalPgn, illegalPgnContent);

      try (SystemErrCapture capture = SystemErrCapture.mute()) {
        int exitCode = ChessCliApplication.execute(new String[]{"pgn-import", tempIllegalPgn.toString()});
        assertEquals(3, exitCode);
        capture.assertContains("CHESS ENGINE ERROR: Illegal move:");
      }
    } finally {
      Files.deleteIfExists(tempIllegalPgn);
    }
  }
}