package org.stvnadore.chess.cli;

import org.jspecify.annotations.Nullable;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.TurnState;
import org.stvnadore.chess.engine.FenCodec;
import org.stvnadore.chess.engine.MoveValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Formats chess positions and turn state snapshots into terminal-ready ASCII/Unicode 8x8 grids.
 */
public final class AsciiBoardRenderer {

  /**
   * Rendering configuration options.
   *
   * @param useUnicode if true, renders Unicode chess glyphs; if false, renders plain ASCII letters
   * @param showCoordinates if true, prints row (1-8) and column (a-h) header labels
   * @param showTurnMetadata if true, displays side panel / footer with turn details
   * @param showMaterialBalance if true, computes and prints captured pieces and material imbalance
   */
  public record RenderOptions(
      boolean useUnicode,
      boolean showCoordinates,
      boolean showTurnMetadata,
      boolean showMaterialBalance
  ) {
    /**
     * Default options using Unicode glyphs, coordinate labels, turn metadata, and material balance.
     *
     * @return default Unicode RenderOptions
     */
    public static RenderOptions defaultUnicode() {
      return new RenderOptions(true, true, true, true);
    }

    /**
     * Options using standard ASCII letters, coordinate labels, turn metadata, and material balance.
     *
     * @return plain ASCII RenderOptions
     */
    public static RenderOptions plainAscii() {
      return new RenderOptions(false, true, true, true);
    }
  }

  /**
   * Material imbalance summary record.
   *
   * @param whiteCaptured pieces captured by white
   * @param blackCaptured pieces captured by black
   * @param scoreDelta point differential relative to white
   */
  public record MaterialBalance(
      List<Piece.PieceRole> whiteCaptured,
      List<Piece.PieceRole> blackCaptured,
      int scoreDelta
  ) {}

  private AsciiBoardRenderer() {
    // Utility class
  }

  /**
   * Renders a BoardState using default Unicode options.
   *
   * @param board current board state position
   * @return formatted board representation string
   */
  public static String render(BoardState board) {
    return render(board, null, null, RenderOptions.defaultUnicode());
  }

  /**
   * Renders a BoardState with customized render options.
   *
   * @param board current board state position
   * @param options rendering configuration options
   * @return formatted board representation string
   */
  public static String render(BoardState board, RenderOptions options) {
    return render(board, null, null, options);
  }

  /**
   * Renders a TurnState snapshot with turn transition metadata.
   *
   * @param turn current turn state snapshot
   * @param prevTurn previous turn state snapshot, if any
   * @param options rendering configuration options
   * @return formatted turn representation string
   */
  public static String renderTurn(TurnState turn, @Nullable TurnState prevTurn, RenderOptions options) {
    Objects.requireNonNull(turn, "turn must not be null");
    BoardState board = FenCodec.parse(turn.fen());
    return render(board, turn, prevTurn, options);
  }

  /**
   * Primary rendering engine generating the complete ASCII/Unicode frame.
   *
   * @param board current board state position
   * @param turn current turn state snapshot, if any
   * @param prevTurn previous turn state snapshot, if any
   * @param options rendering configuration options
   * @return formatted complete board frame string
   */
  public static String render(BoardState board, @Nullable TurnState turn, @Nullable TurnState prevTurn, RenderOptions options) {
    Objects.requireNonNull(board, "board must not be null");
    Objects.requireNonNull(options, "options must not be null");

    StringBuilder sb = new StringBuilder();

    // 1. Top File Coordinates
    if (options.showCoordinates()) {
      sb.append("    a   b   c   d   e   f   g   h\n");
    }
    sb.append("  +---+---+---+---+---+---+---+---+\n");

    // 2. 8x8 Board Grid (Rank 8 down to Rank 1)
    for (int r = 7; r >= 0; r--) {
      int rankNum = r + 1;
      if (options.showCoordinates()) {
        sb.append(rankNum).append(" |");
      } else {
        sb.append("  |");
      }

      for (int f = 0; f < 8; f++) {
        int idx = r * 8 + f;
        @Nullable Piece piece = board.pieceAtIndex(idx);
        String glyph = pieceToGlyph(piece, options.useUnicode());
        sb.append(" ").append(glyph).append(" |");
      }

      if (options.showCoordinates()) {
        sb.append(" ").append(rankNum);
      }
      sb.append("\n");
      sb.append("  +---+---+---+---+---+---+---+---+\n");
    }

    // 3. Bottom File Coordinates
    if (options.showCoordinates()) {
      sb.append("    a   b   c   d   e   f   g   h\n");
    }

    // 4. Turn Metadata Box
    if (options.showTurnMetadata()) {
      sb.append("=======================================================\n");
      long turnNum = (turn != null) ? turn.turnNumber() : board.fullmoveNumber();
      String activeSide = board.activeColor().name();
      String lastMove = (turn != null)
          ? turn.move().from().toAlgebraic() + " -> " + turn.move().to().toAlgebraic() +
            (turn.move().promotion().isPresent() ? " (=" + turn.move().promotion().get() + ")" : "")
          : "Initial Setup";
      int eval = (turn != null) ? turn.evaluationCentipawns() : 0;
      double evalPawns = eval / 100.0;
      String evalStr = String.format(Locale.ROOT, "%+.2f CP", evalPawns);

      boolean inCheck = MoveValidator.isInCheck(board, board.activeColor());
      String statusStr = inCheck ? "IN CHECK" : "ACTIVE (Safe)";

      sb.append(String.format(Locale.ROOT, " Turn: %d (%s) | Last Move: %s | Eval: %s\n",
          turnNum, activeSide, lastMove, evalStr));
      sb.append(String.format(Locale.ROOT, " Status: %s | Halfmove: %d/100 | Fullmove: %d\n",
          statusStr, board.halfmoveClock(), board.fullmoveNumber()));

      // 5. Material Imbalance
      if (options.showMaterialBalance()) {
        MaterialBalance mat = computeMaterialBalance(board);
        String whiteCapStr = formatCapturedList(mat.whiteCaptured(), options.useUnicode(), Piece.PieceColor.BLACK);
        String blackCapStr = formatCapturedList(mat.blackCaptured(), options.useUnicode(), Piece.PieceColor.WHITE);
        String scoreDeltaStr = (mat.scoreDelta() >= 0 ? "+" : "") + mat.scoreDelta();

        sb.append(String.format(Locale.ROOT, " White Captures: [%s] | Black Captures: [%s] (Score: %s)\n",
            whiteCapStr, blackCapStr, scoreDeltaStr));
      }
      sb.append("=======================================================\n");
    }

    return sb.toString();
  }

  /**
   * Computes captured pieces and point differential between White and Black.
   *
   * @param board current board state position
   * @return MaterialBalance summary record
   */
  public static MaterialBalance computeMaterialBalance(BoardState board) {
    Objects.requireNonNull(board, "board must not be null");

    Map<Piece.PieceRole, Integer> whiteCounts = new EnumMap<>(Piece.PieceRole.class);
    Map<Piece.PieceRole, Integer> blackCounts = new EnumMap<>(Piece.PieceRole.class);

    for (Piece.PieceRole role : Piece.PieceRole.values()) {
      whiteCounts.put(role, 0);
      blackCounts.put(role, 0);
    }

    for (int i = 0; i < 64; i++) {
      @Nullable Piece p = board.pieceAtIndex(i);
      if (p != null) {
        if (p.color() == Piece.PieceColor.WHITE) {
          whiteCounts.put(p.role(), whiteCounts.get(p.role()) + 1);
        } else {
          blackCounts.put(p.role(), blackCounts.get(p.role()) + 1);
        }
      }
    }

    // Standard starting counts: P:8, N:2, B:2, R:2, Q:1, K:1
    Map<Piece.PieceRole, Integer> initialCounts = Map.of(
        Piece.PieceRole.PAWN, 8,
        Piece.PieceRole.KNIGHT, 2,
        Piece.PieceRole.BISHOP, 2,
        Piece.PieceRole.ROOK, 2,
        Piece.PieceRole.QUEEN, 1,
        Piece.PieceRole.KING, 1
    );

    List<Piece.PieceRole> whiteCaptured = new ArrayList<>(); // Black pieces captured by White
    List<Piece.PieceRole> blackCaptured = new ArrayList<>(); // White pieces captured by Black

    int whiteMaterial = 0;
    int blackMaterial = 0;

    for (Map.Entry<Piece.PieceRole, Integer> entry : initialCounts.entrySet()) {
      Piece.PieceRole role = entry.getKey();
      int initial = entry.getValue();
      int currentW = whiteCounts.get(role);
      int currentB = blackCounts.get(role);

      int val = pieceValue(role);
      whiteMaterial += currentW * val;
      blackMaterial += currentB * val;

      if (currentB < initial) {
        for (int k = 0; k < (initial - currentB); k++) {
          whiteCaptured.add(role);
        }
      }
      if (currentW < initial) {
        for (int k = 0; k < (initial - currentW); k++) {
          blackCaptured.add(role);
        }
      }
    }

    int scoreDelta = whiteMaterial - blackMaterial;
    return new MaterialBalance(
        Collections.unmodifiableList(whiteCaptured),
        Collections.unmodifiableList(blackCaptured),
        scoreDelta
    );
  }

  private static int pieceValue(Piece.PieceRole role) {
    return switch (role) {
      case PAWN -> 1;
      case KNIGHT, BISHOP -> 3;
      case ROOK -> 5;
      case QUEEN -> 9;
      case KING -> 0;
    };
  }

  private static String pieceToGlyph(@Nullable Piece piece, boolean useUnicode) {
    if (piece == null) {
      return " ";
    }
    if (useUnicode) {
      if (piece.color() == Piece.PieceColor.WHITE) {
        return switch (piece.role()) {
          case KING -> "♔";
          case QUEEN -> "♕";
          case ROOK -> "♖";
          case BISHOP -> "♗";
          case KNIGHT -> "♘";
          case PAWN -> "♙";
        };
      } else {
        return switch (piece.role()) {
          case KING -> "♚";
          case QUEEN -> "♛";
          case ROOK -> "♜";
          case BISHOP -> "♝";
          case KNIGHT -> "♞";
          case PAWN -> "♟";
        };
      }
    } else {
      char c = switch (piece.role()) {
        case KING -> 'K';
        case QUEEN -> 'Q';
        case ROOK -> 'R';
        case BISHOP -> 'B';
        case KNIGHT -> 'N';
        case PAWN -> 'P';
      };
      return piece.color() == Piece.PieceColor.WHITE ? String.valueOf(c) : String.valueOf(Character.toLowerCase(c));
    }
  }

  private static String formatCapturedList(List<Piece.PieceRole> roles, boolean useUnicode, Piece.PieceColor pieceColor) {
    if (roles.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < roles.size(); i++) {
      if (i > 0) sb.append(" ");
      sb.append(pieceToGlyph(new Piece(pieceColor, roles.get(i)), useUnicode));
    }
    return sb.toString();
  }
}
