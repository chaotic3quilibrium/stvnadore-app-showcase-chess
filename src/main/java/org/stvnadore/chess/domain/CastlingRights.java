package org.stvnadore.chess.domain;

/**
 * Immutable product tuple record representing player castling privileges under FIDE rules.
 *
 * @param whiteKingside true if White retains kingside castling rights
 * @param whiteQueenside true if White retains queenside castling rights
 * @param blackKingside true if Black retains kingside castling rights
 * @param blackQueenside true if Black retains queenside castling rights
 */
public record CastlingRights(
    boolean whiteKingside,
    boolean whiteQueenside,
    boolean blackKingside,
    boolean blackQueenside
) {

  /** Initial standard castling privileges where all four options are available. */
  public static final CastlingRights ALL = new CastlingRights(true, true, true, true);

  /** Zero castling privileges remaining. */
  public static final CastlingRights NONE = new CastlingRights(false, false, false, false);

  /**
   * Revokes White castling privileges upon White king movement.
   *
   * @return new CastlingRights record
   */
  public CastlingRights revokeWhite() {
    return new CastlingRights(false, false, blackKingside, blackQueenside);
  }

  /**
   * Revokes Black castling privileges upon Black king movement.
   *
   * @return new CastlingRights record
   */
  public CastlingRights revokeBlack() {
    return new CastlingRights(whiteKingside, whiteQueenside, false, false);
  }
}
