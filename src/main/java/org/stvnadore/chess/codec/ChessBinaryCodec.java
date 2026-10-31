package org.stvnadore.chess.codec;

import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.readers.StvnTupleReader;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * High-performance binary codec for STVN Chess Game states utilizing Strategy 0x07 (ExplicitSha256).
 */
public class ChessBinaryCodec {

  private final String schemaSourceText;
  private final StvnDocument schemaDocument;
  private final StvnSchema gameHistorySchema;
  private final byte[] expectedSha256Digest;
  private final String casHashHex;
  private final String repositoryCasHashHex;

  /**
   * Constructs a codec by compiling and hashing the canonical schema source text.
   *
   * @param schemaSourceText raw {@code .stvn_d} schema content
   */
  public ChessBinaryCodec(String schemaSourceText) {
    this.schemaSourceText = Objects.requireNonNull(schemaSourceText, "schemaSourceText must not be null");
    this.schemaDocument = ChessAstMapper.compileSchemaDocument(schemaSourceText);
    this.gameHistorySchema = ChessAstMapper.resolveGameHistorySchema(this.schemaDocument.defs());
    this.expectedSha256Digest = StvnSchemaHasher.computeSha256(this.gameHistorySchema);
    this.casHashHex = HexFormat.of().formatHex(this.expectedSha256Digest);
    this.repositoryCasHashHex = computeFlattenedRepositoryCasHash(this.schemaDocument, schemaSourceText);
  }

  /**
   * Returns the 64-character lowercase hex SHA-256 wire digest of the root {@code :GameHistory} schema.
   *
   * @return 64-character hex hash string
   */
  public String getCasHashHex() {
    return casHashHex;
  }

  /**
   * Returns the 64-character lowercase hex SHA-256 repository CAS digest of the flattened {@code .stvn_d} module.
   *
   * @return 64-character repository CAS hash string
   */
  public String getRepositoryCasHashHex() {
    return repositoryCasHashHex;
  }

  /**
   * Returns the resolved pure-IR {@link StvnSchema} for {@code :gaming/chess/GameHistory}.
   *
   * @return pure-IR root tuple schema
   */
  public StvnSchema getGameHistorySchema() {
    return gameHistorySchema;
  }

  /**
   * Returns the 32-byte binary SHA-256 digest of this schema.
   *
   * @return 32-byte digest array
   */
  public byte[] getExpectedSha256Digest() {
    return expectedSha256Digest.clone();
  }

  /**
   * Encodes a {@link GameHistory} domain record into an STVN binary byte buffer using Strategy 0x07
   * and appends a 4-byte CRC-32C trailer framing.
   *
   * @param game the game history to encode
   * @return read-only little-endian ByteBuffer containing binary payload
   */
  public ByteBuffer encode(GameHistory game) {
    Objects.requireNonNull(game, "game must not be null");

    StvnValue ast = ChessAstMapper.toStvnValue(game, gameHistorySchema);
    var strategy = new SchemaIdentityStrategy.ExplicitSha256(expectedSha256Digest);
    var encoder = new StvnBinaryEncoder(true, strategy, true);
    return encoder.encode(ast);
  }

  /**
   * Encodes the canonical {@code #DEFS} schema definition into an STVN {@code .stvn_bd} binary byte buffer
   * using Strategy 0x08 ({@link SchemaIdentityStrategy.SelfDescribingSchema}) with CRC-32C trailer framing.
   *
   * @return read-only little-endian ByteBuffer containing binary schema envelope
   */
  public ByteBuffer encodeSchemaSelfDescribing() {
    StvnDocumentMeta meta = schemaDocument.meta()
        .orElseGet(() -> StvnDocumentMeta.ofKind(StvnDocumentKind.DEFS));
    var strategy = new SchemaIdentityStrategy.SelfDescribingSchema(schemaSourceText);
    var encoder = new StvnBinaryEncoder(true, strategy, true);
    return encoder.encodeDefinitionsText(strategy.schemaSourceText(), meta);
  }

  /**
   * Decodes an STVN binary byte buffer back into a {@link GameHistory} domain record.
   * Enforces zero-trust schema validation via {@link StvnBinaryDecoder}.
   *
   * @param buffer binary byte buffer
   * @return unpacked {@link GameHistory} record
   * @throws PoisonedRegistryPayloadException if the binary header hash does not match the schema
   */
  public GameHistory decode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer, "buffer must not be null");

    var rootPointer = StvnBinaryDecoder.open(buffer, null, gameHistorySchema, null);
    StvnValue unpackedAst = StvnBinaryDecoder.unpack(rootPointer, Optional.of(gameHistorySchema));

    return ChessAstMapper.fromStvnAst(unpackedAst);
  }

  /**
   * Opens a zero-copy root tuple reader over an STVN binary byte buffer.
   *
   * @param buffer binary byte buffer
   * @return a zero-copy {@link StvnTupleReader} for direct memory inspection
   * @throws PoisonedRegistryPayloadException if the binary header hash does not match the schema
   */
  public StvnTupleReader openRootTuple(ByteBuffer buffer) {
    Objects.requireNonNull(buffer, "buffer must not be null");
    var rootPointer = StvnBinaryDecoder.open(buffer, null, gameHistorySchema, null);
    return StvnBinaryDecoder.readRootTuple(rootPointer, Optional.of(gameHistorySchema));
  }

  /**
   * Injects corruption into a binary payload by flipping bytes in the SHA-256 header hash.
   *
   * @param validPayload original valid byte array
   * @return corrupted byte array designed to trigger {@link PoisonedRegistryPayloadException}
   */
  public static byte[] poisonPayload(byte[] validPayload) {
    Objects.requireNonNull(validPayload, "validPayload must not be null");
    if (validPayload.length < 38) {
      throw new IllegalArgumentException("Payload too short for Strategy 0x07 header: " + validPayload.length);
    }
    // Byte 0..3: Magic, Byte 4: Control, Byte 5: MetaControlByte
    byte metaControl = validPayload[5];
    int digestOffset = 6;
    if ((metaControl & 0x04) != 0) { // HAS_NAME
      int nameLen = Byte.toUnsignedInt(validPayload[digestOffset]);
      digestOffset += 1 + nameLen;
    }
    if ((metaControl & 0x08) != 0) { // HAS_DOMAIN
      int domainLen = Byte.toUnsignedInt(validPayload[digestOffset]);
      digestOffset += 1 + domainLen;
    }
    if (validPayload.length < digestOffset + 32) {
      throw new IllegalArgumentException("Payload too short for Strategy 0x07 header: " + validPayload.length);
    }
    byte[] corrupted = validPayload.clone();
    // Corrupt the first byte of the 32-byte SHA-256 digest
    corrupted[digestOffset] = (byte) (corrupted[digestOffset] ^ 0xFF);
    // If CRC-32C trailer is enabled (Byte 4 Bit 7 == 1), recompute trailer so transport passes
    if ((corrupted[4] & (byte) 0x80) != 0 && corrupted.length >= 41) {
      CRC32C crc = new CRC32C();
      crc.update(corrupted, 0, corrupted.length - 4);
      int newCrc = (int) crc.getValue();
      ByteBuffer.wrap(corrupted).order(ByteOrder.LITTLE_ENDIAN).putInt(corrupted.length - 4, newCrc);
    }
    return corrupted;
  }

  private static String computeFlattenedRepositoryCasHash(StvnDocument schemaDoc, String sourceText) {
    if (!schemaDoc.hasMeta()) {
      return "";
    }
    try {
      StvnDocumentMeta meta = schemaDoc.requireMeta();
      String stem = meta.name().orElse("chess_turn");
      String domainSuffix = meta.domain().map(d -> "." + d).orElse("");
      String unitName = stem + domainSuffix + ".stvn_d";
      String flattened = StvnSchemaFlattener.flatten(
          Map.of(unitName, sourceText),
          unitName
      );
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(flattened.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
    }
  }
}
