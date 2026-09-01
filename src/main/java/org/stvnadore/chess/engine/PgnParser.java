package org.stvnadore.chess.engine;

import org.jspecify.annotations.Nullable;
import org.stvnadore.chess.domain.BoardState;
import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.chess.domain.Move;
import org.stvnadore.chess.domain.Piece;
import org.stvnadore.chess.domain.Square;
import org.stvnadore.chess.domain.TurnState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust parser for Portable Game Notation (PGN) chess match records.
 * Resolves standard algebraic notation (SAN) against the chess rule engine.
 */
public final class PgnParser {

  private static final Pattern TAG_PATTERN = Pattern.compile("\\[\\s*([A-Za-z0-9_]+)\\s+\"([^\"]*)\"\\s*\\]");
  private static final Pattern COMMENT_BRACE_PATTERN = Pattern.compile("\\{[^}]*\\}", Pattern.DOTALL);
  private static final Pattern COMMENT_LINE_PATTERN = Pattern.compile(";.*$");
  private static final Pattern VARIATION_PATTERN = Pattern.compile("\\([^)]*\\)", Pattern.DOTALL);
  private static final Pattern NAG_PATTERN = Pattern.compile("\\$\\d+");
  private static final Pattern MOVE_NUMBER_PATTERN = Pattern.compile("^\\d+\\.+");

  private PgnParser() {
    // Utility class
  }

  /**
   * Parses standard PGN text into an immutable GameHistory domain record.
   *
   * @param pgnContent raw PGN file text
   * @return validated GameHistory record
   * @throws PgnParseException if headers or structure are invalid
   * @throws IllegalMoveException if an illegal or ambiguous move is encountered
   */
  public static GameHistory parse(String pgnContent) {
    return parse(pgnContent, "pgn-match-" + System.currentTimeMillis());
  }

  /**
   * Parses standard PGN text with a fallback game identifier.
   *
   * @param pgnContent raw PGN file text
   * @param fallbackGameId fallback game ID if Event tag is missing
   * @return validated GameHistory record
   */
  public static GameHistory parse(String pgnContent, String fallbackGameId) {
    Objects.requireNonNull(pgnContent, "pgnContent must not be null");
    Objects.requireNonNull(fallbackGameId, "fallbackGameId must not be null");

    // 1. Extract Tag Headers
    Map<String, String> tags = new LinkedHashMap<>();
    Matcher tagMatcher = TAG_PATTERN.matcher(pgnContent);
    while (tagMatcher.find()) {
      tags.put(tagMatcher.group(1), tagMatcher.group(2));
    }

    String gameId = tags.getOrDefault("Event", fallbackGameId);
    String whitePlayer = tags.getOrDefault("White", "Unknown-White");
    String blackPlayer = tags.getOrDefault("Black", "Unknown-Black");
    String resultHeader = tags.get("Result");

    // 2. Extract Move Section
    String moveSection = pgnContent.replaceAll("\\[[^\\]]*\\]", " ");
    moveSection = COMMENT_BRACE_PATTERN.matcher(moveSection).replaceAll(" ");
    moveSection = COMMENT_LINE_PATTERN.matcher(moveSection).replaceAll(" ");
    moveSection = VARIATION_PATTERN.matcher(moveSection).replaceAll(" ");
    moveSection = NAG_PATTERN.matcher(moveSection).replaceAll(" ");

    String[] rawTokens = moveSection.trim().split("\\s+");
    List<String> moveTokens = new ArrayList<>();

    for (String token : rawTokens) {
      if (token.isBlank()) continue;
      String cleanToken = MOVE_NUMBER_PATTERN.matcher(token).replaceAll("").trim();
      if (cleanToken.isEmpty()) continue;

      if (cleanToken.equals("1-0") || cleanToken.equals("0-1") ||
          cleanToken.equals("1/2-1/2") || cleanToken.equals("*")) {
        if (resultHeader == null) {
          resultHeader = cleanToken;
        }
        continue;
      }
      moveTokens.add(cleanToken);
    }

    // 3. Sequentially resolve moves on BoardState
    BoardState currentBoard = BoardState.initial();
    List<TurnState> turns = new ArrayList<>(moveTokens.size());
    long turnNumber = 1;

    for (String token : moveTokens) {
      Move move = resolveSanMove(currentBoard, token);
      BoardState nextBoard = MoveValidator.applyMove(currentBoard, move);
      String fen = FenCodec.format(nextBoard);

      turns.add(new TurnState(turnNumber++, currentBoard.activeColor(), move, fen, 0));
      currentBoard = nextBoard;
    }

    // 4. Resolve Terminal Outcome
    Optional<GameHistory.TerminalOutcome> outcome = Optional.empty();
    if ("1-0".equals(resultHeader)) {
      outcome = Optional.of(GameHistory.TerminalOutcome.WHITE_WIN);
    } else if ("0-1".equals(resultHeader)) {
      outcome = Optional.of(GameHistory.TerminalOutcome.BLACK_WIN);
    } else if ("1/2-1/2".equals(resultHeader)) {
      outcome = Optional.of(GameHistory.TerminalOutcome.DRAW);
    } else {
      List<Move> terminalLegalMoves = MoveValidator.generateLegalMoves(currentBoard);
      outcome = TerminalDetector.detectOutcome(currentBoard, terminalLegalMoves);
    }

    return new GameHistory(gameId, whitePlayer, blackPlayer, turns, outcome);
  }

  /**
   * Resolves a single SAN token (e.g. "e4", "Nf3", "O-O", "Nbd7", "e8=Q#") against candidate legal moves.
   *
   * @param board current board state position
   * @param sanToken Standard Algebraic Notation move token
   * @return resolved legal Move record
   */
  public static Move resolveSanMove(BoardState board, String sanToken) {
    Objects.requireNonNull(board, "board must not be null");
    Objects.requireNonNull(sanToken, "sanToken must not be null");

    String clean = sanToken.replace("+", "").replace("#", "").replace("!", "").replace("?", "").trim();
    List<Move> legalMoves = MoveValidator.generateLegalMoves(board);
    Piece.PieceColor activeColor = board.activeColor();

    // 1. Kingside Castling
    if (clean.equals("O-O") || clean.equals("0-0")) {
      Square from = (activeColor == Piece.PieceColor.WHITE) ? Square.fromAlgebraic("e1") : Square.fromAlgebraic("e8");
      Square to = (activeColor == Piece.PieceColor.WHITE) ? Square.fromAlgebraic("g1") : Square.fromAlgebraic("g8");
      return legalMoves.stream()
          .filter(m -> m.from().equals(from) && m.to().equals(to))
          .findFirst()
          .orElseThrow(() -> new IllegalMoveException("Illegal kingside castling: " + sanToken + " on board: " + FenCodec.format(board)));
    }

    // 2. Queenside Castling
    if (clean.equals("O-O-O") || clean.equals("0-0-0")) {
      Square from = (activeColor == Piece.PieceColor.WHITE) ? Square.fromAlgebraic("e1") : Square.fromAlgebraic("e8");
      Square to = (activeColor == Piece.PieceColor.WHITE) ? Square.fromAlgebraic("c1") : Square.fromAlgebraic("c8");
      return legalMoves.stream()
          .filter(m -> m.from().equals(from) && m.to().equals(to))
          .findFirst()
          .orElseThrow(() -> new IllegalMoveException("Illegal queenside castling: " + sanToken + " on board: " + FenCodec.format(board)));
    }

    // 3. Pawn Promotion Check
    Optional<Move.PromotionRole> promoRole = Optional.empty();
    int promoIdx = clean.indexOf('=');
    if (promoIdx < 0) {
      // Handle alternative promo syntax like e8Q or e8(Q) or e8/Q
      if (clean.length() >= 3 && Character.isUpperCase(clean.charAt(clean.length() - 1)) &&
          Character.isDigit(clean.charAt(clean.length() - 2))) {
        char promoChar = clean.charAt(clean.length() - 1);
        promoRole = Optional.of(charToPromoRole(promoChar));
        clean = clean.substring(0, clean.length() - 1);
      }
    } else {
      char promoChar = clean.charAt(promoIdx + 1);
      promoRole = Optional.of(charToPromoRole(promoChar));
      clean = clean.substring(0, promoIdx);
    }

    // 4. Extract Destination Square (last 2 characters)
    if (clean.length() < 2) {
      throw new PgnParseException("Invalid SAN move token: " + sanToken);
    }
    String destSquareStr = clean.substring(clean.length() - 2);
    Square destSquare;
    try {
      destSquare = Square.fromAlgebraic(destSquareStr);
    } catch (Exception e) {
      throw new PgnParseException("Invalid destination square in SAN: " + sanToken, e);
    }

    // 5. Extract Piece Role and Disambiguation Specifiers
    String prefix = clean.substring(0, clean.length() - 2).replace("x", "");
    Piece.PieceRole expectedRole = Piece.PieceRole.PAWN;
    @Nullable Character disambigFile = null;
    @Nullable Integer disambigRank = null;

    if (!prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0))) {
      expectedRole = charToPieceRole(prefix.charAt(0));
      prefix = prefix.substring(1);
    }

    for (int i = 0; i < prefix.length(); i++) {
      char ch = prefix.charAt(i);
      if (ch >= 'a' && ch <= 'h') {
        disambigFile = ch;
      } else if (ch >= '1' && ch <= '8') {
        disambigRank = ch - '0';
      }
    }

    // 6. Filter Candidate Legal Moves
    Piece.PieceRole finalExpectedRole = expectedRole;
    Optional<Move.PromotionRole> finalPromoRole = promoRole;
    @Nullable Character finalDisambigFile = disambigFile;
    @Nullable Integer finalDisambigRank = disambigRank;

    List<Move> matchingMoves = legalMoves.stream().filter(m -> {
      if (!m.to().equals(destSquare)) return false;
      Piece piece = board.pieceAt(m.from()).orElse(null);
      if (piece == null || piece.color() != activeColor || piece.role() != finalExpectedRole) return false;
      if (finalDisambigFile != null && m.from().file().name().toLowerCase(Locale.ROOT).charAt(0) != finalDisambigFile) return false;
      if (finalDisambigRank != null && m.from().rank() != finalDisambigRank) return false;
      if (finalPromoRole.isPresent()) {
        return m.promotion().isPresent() && m.promotion().get() == finalPromoRole.get();
      } else {
        return m.promotion().isEmpty();
      }
    }).toList();

    if (matchingMoves.isEmpty()) {
      throw new IllegalMoveException("Illegal move: '" + sanToken + "' on board: " + FenCodec.format(board));
    }
    if (matchingMoves.size() > 1) {
      throw new IllegalMoveException("Ambiguous move: '" + sanToken + "' matched " + matchingMoves.size() +
          " candidate moves on board: " + FenCodec.format(board));
    }

    return matchingMoves.get(0);
  }

  private static Piece.PieceRole charToPieceRole(char c) {
    return switch (c) {
      case 'N' -> Piece.PieceRole.KNIGHT;
      case 'B' -> Piece.PieceRole.BISHOP;
      case 'R' -> Piece.PieceRole.ROOK;
      case 'Q' -> Piece.PieceRole.QUEEN;
      case 'K' -> Piece.PieceRole.KING;
      default -> throw new PgnParseException("Unknown piece identifier in SAN: " + c);
    };
  }

  private static Move.PromotionRole charToPromoRole(char c) {
    return switch (Character.toUpperCase(c)) {
      case 'Q' -> Move.PromotionRole.QUEEN;
      case 'R' -> Move.PromotionRole.ROOK;
      case 'B' -> Move.PromotionRole.BISHOP;
      case 'N' -> Move.PromotionRole.KNIGHT;
      default -> throw new PgnParseException("Unknown promotion role: " + c);
    };
  }
}
