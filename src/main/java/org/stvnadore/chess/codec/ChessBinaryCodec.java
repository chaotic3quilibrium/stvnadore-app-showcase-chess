package org.stvnadore.chess.codec;

import org.stvnadore.chess.domain.GameHistory;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.readers.StvnTupleReader;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * High-performance binary codec for STVN Chess Game states utilizing Strategy 0x07 (ExplicitSha256).
 */
public class ChessBinaryCodec {

  private final String schemaSourceText;
  private final ResolvedSchema resolvedSchema;
  private final byte[] expectedSha256Digest;
  private final String casHashHex;

  /**
   * Constructs a codec by compiling and hashing the canonical schema source text.
   *
   * @param schemaSourceText raw .stvn_inclf schema content
   */
  public ChessBinaryCodec(String schemaSourceText) {
    this.schemaSourceText = Objects.requireNonNull(schemaSourceText, "schemaSourceText must not be null");
    
    // Compile dummy document with schema to extract ResolvedSchema
    String wrapperDoc = wrapSchemaWithDummyType(schemaSourceText);
    StvnValue sampleValue = StvnCompiler.compile(wrapperDoc)
        .orElseThrow(() -> new IllegalStateException("Failed to compile schema definition"));
    
    this.resolvedSchema = sampleValue.schema();
    this.expectedSha256Digest = StvnSchemaHasher.computeSha256(this.resolvedSchema);
    this.casHashHex = HexFormat.of().formatHex(this.expectedSha256Digest);
  }

  /**
   * Returns the 64-character lowercase hex CAS hash of this schema.
   *
   * @return 64-character hex hash string
   */
  public String getCasHashHex() {
    return casHashHex;
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
   * Returns the compiled ResolvedSchema definition for this codec.
   *
   * @return resolved schema instance
   */
  public ResolvedSchema getResolvedSchema() {
    return resolvedSchema;
  }

  /**
   * Encodes a GameHistory domain record into an STVN binary byte buffer using Strategy 0x07
   * and appends a 4-byte CRC-32C trailer framing.
   *
   * @param game the game history to encode
   * @return read-only little-endian ByteBuffer containing binary payload
   */
  public ByteBuffer encode(GameHistory game) {
    return encode(game, true);
  }

  /**
   * Encodes a GameHistory domain record into an STVN binary byte buffer using Strategy 0x07
   * with optional CRC-32C trailer framing.
   *
   * @param game             the game history to encode
   * @param hasTrailerCrc32c if true, appends 4-byte CRC-32C trailer and sets Bit 7 of Byte 4
   * @return read-only little-endian ByteBuffer containing binary payload
   */
  public ByteBuffer encode(GameHistory game, boolean hasTrailerCrc32c) {
    Objects.requireNonNull(game, "game must not be null");

    StvnValue ast = ChessAstMapper.toStvnAst(game, schemaSourceText);
    var strategy = new SchemaIdentityStrategy.ExplicitSha256(expectedSha256Digest);
    var encoder = new StvnBinaryEncoder(true, strategy, hasTrailerCrc32c);
    return encoder.encode(ast);
  }

  /**
   * Decodes an STVN binary byte buffer back into a GameHistory domain record.
   * Enforces zero-trust schema validation via StvnBinaryDecoder.
   *
   * @param buffer binary byte buffer
   * @return unpacked GameHistory record
   * @throws PoisonedRegistryPayloadException if the binary header hash does not match the schema
   */
  public GameHistory decode(ByteBuffer buffer) {
    Objects.requireNonNull(buffer, "buffer must not be null");

    var rootPointer = StvnBinaryDecoder.open(buffer, null, null, null);
    StvnValue unpackedAst = StvnBinaryDecoder.unpack(rootPointer, Optional.of(resolvedSchema));

    return ChessAstMapper.fromStvnAst(unpackedAst);
  }

  /**
   * Opens a zero-copy root tuple reader over an STVN binary byte buffer.
   *
   * @param buffer binary byte buffer
   * @return a zero-copy {@link StvnTupleReader} for direct memory inspection
   */
  public StvnTupleReader openRootTuple(ByteBuffer buffer) {
    Objects.requireNonNull(buffer, "buffer must not be null");
    var rootPointer = StvnBinaryDecoder.open(buffer);
    return StvnBinaryDecoder.readRootTuple(rootPointer, Optional.of(resolvedSchema));
  }

  /**
   * Injects corruption into a binary payload by flipping bytes in the SHA-256 header hash.
   *
   * @param validPayload original valid byte array
   * @return corrupted byte array designed to trigger PoisonedRegistryPayloadException
   */
  public static byte[] poisonPayload(byte[] validPayload) {
    Objects.requireNonNull(validPayload, "validPayload must not be null");
    if (validPayload.length < 37) {
      throw new IllegalArgumentException("Payload too short for Strategy 0x07 header: " + validPayload.length);
    }
    byte[] corrupted = validPayload.clone();
    // Offset 5 is the first byte of the 32-byte SHA-256 digest in Control Byte 0x07
    corrupted[5] = (byte) (corrupted[5] ^ 0xFF);
    // If CRC-32C trailer is enabled (Byte 4 Bit 7 == 1), recompute trailer so transport passes
    if ((corrupted[4] & (byte) 0x80) != 0 && corrupted.length >= 41) {
      CRC32C crc = new CRC32C();
      crc.update(corrupted, 0, corrupted.length - 4);
      int newCrc = (int) crc.getValue();
      ByteBuffer.wrap(corrupted).order(ByteOrder.LITTLE_ENDIAN).putInt(corrupted.length - 4, newCrc);
    }
    return corrupted;
  }

  private static String wrapSchemaWithDummyType(String schemaContent) {
    int defsIdx = schemaContent.indexOf(":defs");
    int openBrace = schemaContent.indexOf('{', defsIdx);
    int closeBrace = -1;
    int depth = 0;
    for (int i = openBrace; i < schemaContent.length(); i++) {
      char c = schemaContent.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          closeBrace = i;
          break;
        }
      }
    }
    String defsBlock = schemaContent.substring(defsIdx, closeBrace + 1);
    if (defsBlock.contains(":package :org/stvnadore/chess")) {
      int lastBrace = defsBlock.lastIndexOf('}');
      defsBlock = defsBlock.substring(0, lastBrace) + "  :use [ :org/stvnadore/chess { #strip } ]\n  }";
    }
    return "{\n  " + defsBlock + "\n  :type :GameHistory\n  :body ( \"test\" \"white\" \"black\" [] #None )\n}";
  }
}