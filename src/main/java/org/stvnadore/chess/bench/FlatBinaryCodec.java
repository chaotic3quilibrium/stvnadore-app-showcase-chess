package org.stvnadore.chess.bench;

import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact fixed-layout binary codec without schema identifiers or self-describing AST metadata.
 * Serves as an empirical baseline for minimum possible binary representation.
 */
public final class FlatBinaryCodec {

  private static final int MAGIC = 0x43484553; // "CHES"

  private FlatBinaryCodec() {
    // Utility class
  }

  /**
   * Encodes a GameHistory into a raw byte array.
   *
   * @param game the GameHistory record to encode
   * @return raw flat binary byte array
   */
  public static byte[] encode(GameHistory game) {
    Objects.requireNonNull(game, "game must not be null");
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(baos)) {

      dos.writeInt(MAGIC);
      dos.writeUTF(game.gameId());
      dos.writeUTF(game.whitePlayer());
      dos.writeUTF(game.blackPlayer());

      // Result flag: 0=None, 1=WHITE_WIN, 2=BLACK_WIN, 3=DRAW
      if (game.result().isEmpty()) {
        dos.writeByte(0);
      } else {
        dos.writeByte(switch (game.result().get()) {
          case WHITE_WIN -> 1;
          case BLACK_WIN -> 2;
          case DRAW -> 3;
        });
      }

      dos.writeInt(game.turns().size());
      for (TurnState turn : game.turns()) {
        dos.writeInt((int) turn.turnNumber());
        dos.writeByte(turn.activeColor() == Piece.PieceColor.WHITE ? 1 : 2);

        // Move encoding
        Move m = turn.move();
        dos.writeByte(m.from().toIndex());
        dos.writeByte(m.to().toIndex());
        if (m.promotion().isEmpty()) {
          dos.writeByte(0);
        } else {
          dos.writeByte(switch (m.promotion().get()) {
            case KNIGHT -> 1;
            case BISHOP -> 2;
            case ROOK -> 3;
            case QUEEN -> 4;
          });
        }
        dos.writeBoolean(m.isCapture());
        dos.writeByte(m.halfmovesSincePawnOrCapture());
        dos.writeShort(turn.evaluationCentipawns());
        dos.writeUTF(turn.fen());
      }

      dos.flush();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to encode flat binary", e);
    }
  }

  /**
   * Decodes a raw byte array back into a GameHistory.
   *
   * @param bytes raw flat binary byte array
   * @return decoded GameHistory domain record
   */
  public static GameHistory decode(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
         DataInputStream dis = new DataInputStream(bais)) {

      int magic = dis.readInt();
      if (magic != MAGIC) {
        throw new IllegalArgumentException("Invalid flat binary magic: " + Integer.toHexString(magic));
      }

      String gameId = dis.readUTF();
      String whitePlayer = dis.readUTF();
      String blackPlayer = dis.readUTF();

      byte resultByte = dis.readByte();
      Optional<GameHistory.TerminalOutcome> result = switch (resultByte) {
        case 1 -> Optional.of(GameHistory.TerminalOutcome.WHITE_WIN);
        case 2 -> Optional.of(GameHistory.TerminalOutcome.BLACK_WIN);
        case 3 -> Optional.of(GameHistory.TerminalOutcome.DRAW);
        default -> Optional.empty();
      };

      int turnCount = dis.readInt();
      List<TurnState> turns = new ArrayList<>(turnCount);

      for (int i = 0; i < turnCount; i++) {
        long turnNumber = dis.readInt();
        Piece.PieceColor color = dis.readByte() == 1 ? Piece.PieceColor.WHITE : Piece.PieceColor.BLACK;

        Square from = Square.fromIndex(dis.readByte());
        Square to = Square.fromIndex(dis.readByte());

        byte promoByte = dis.readByte();
        Optional<Move.PromotionRole> promo = switch (promoByte) {
          case 1 -> Optional.of(Move.PromotionRole.KNIGHT);
          case 2 -> Optional.of(Move.PromotionRole.BISHOP);
          case 3 -> Optional.of(Move.PromotionRole.ROOK);
          case 4 -> Optional.of(Move.PromotionRole.QUEEN);
          default -> Optional.empty();
        };

        boolean isCapture = dis.readBoolean();
        int halfmoves = dis.readByte() & 0xFF;
        int eval = dis.readShort();
        String fen = dis.readUTF();

        Move move = new Move(from, to, promo, isCapture, halfmoves);
        turns.add(new TurnState(turnNumber, color, move, fen, eval));
      }

      return new GameHistory(gameId, whitePlayer, blackPlayer, turns, result);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to decode flat binary", e);
    }
  }
}
