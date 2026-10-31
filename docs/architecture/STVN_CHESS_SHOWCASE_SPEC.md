# STVN Architecture Specification: Chess Reference Application & VOP Domain Standards

**Document ID:** `STVN-SPEC-CHESS-01`  
**Status:** Canonical Architecture Specification  
**Version:** `2.0.0-SNAPSHOT`  
**Target Repository:** `stvnadore-app-showcase-chess` (`ij_stvnadore_example_chess`)  
**Target Version Baseline:** `2.0.0-SNAPSHOT`  
**Governing Standard:** Simplified Technical English (STE-01 through STE-07)  
**Foundational Baselines:** `STVN_LANGUAGE_SPEC.md` (STVN 2.0.0), `STVN-VOP-MANIFESTO.md`, `STVN_REPOSITORY_SPEC.md`  

---

## 1. Scope & Ecosystem Authority

### 1.1 The Ecosystem Role of the Chess Showcase

The STVN Chess Showcase application (`stvnadore-app-showcase-chess`) serves as the canonical reference implementation of Value-Oriented Programming (VOP) and STVN wire protocols. Downstream microservices, client libraries, and distributed engines replicate its architectural patterns.

The chess reference application demonstrates four core capabilities:
1. **Domain Modeling with Zero Nulls:** Models the complete rules of chess under FIDE standards using immutable Java records and sealed algebraic types.
2. **Lossless Bidirectional Pure-IR Mapping:** Converts domain models to and from pure-IR `StvnValue` trees via `StvnMapper` with exact `BigInteger` perimeter narrowing (`intValueExact()`, `longValueExact()`).
3. **Zero-Trust Binary Framing:** Encodes game states using Binary Encoding Strategy `0x7` (`ExplicitSha256`) with IEEE 802.3 CRC-32C trailer validation.
4. **Registry Client Integration:** Publishes `.stvn_d` and Strategy `0x08` `.stvn_bd` `#DEFS` schema envelopes to `stvnadore-repository` and retrieves canonical flattened schemas by 2/64 SHA-256 CAS hash.

```mermaid
flowchart LR
    Core["stvnadore-core:2.0.0-SNAPSHOT (Compiler, Pure IR, Binary Codecs)"] --> Chess["stvnadore-app-showcase-chess (Reference Domain Model)"]
    Repo["stvnadore-repository:2.0.0-SNAPSHOT (Schema Registry and 2/64 CAS)"] <-->|"Publish and Fetch by CAS Hash"| Chess
    Chess --> ClientApp["Downstream Domain Services (Consumer Implementations)"]
```

### 1.2 Multi-Tier Integration Architecture

The showcase integrates three ecosystem layers:
- **Core SDK Tier (`stvnadore-core`):** Supplies `StvnCompiler`, `StvnMapper`, `StvnSchemaFlattener`, `StvnSchemaHasher`, `StvnBinaryEncoder`, `StvnBinaryDecoder`, and pure `org.stvnadore.core.ir.*` records.
- **Repository Registry Tier (`stvnadore-repository`):** Provides Content-Addressable Storage (CAS) and enforces the Nominal Bijectivity Invariant ($1:1$ Law) via `version_catalog`.
- **Domain Showcase Tier (`stvnadore-app-showcase-chess`):** Integrates the FIDE move validation engine, pure-IR mapper, wire benchmarker, and terminal CLI.

---

## 2. Domain-to-STVN Type Mapping Invariants (STVN 2.0.0 Standards)

The chess domain schema (`src/main/resources/schemas/chess_turn.gaming.stvn_d`) defines all types in `:package :gaming/chess`. Under STVN 2.0.0, the schema conforms to 5-tier decimal facet ordering, discrete half-open intervals, and character cardinality rules.

```mermaid
flowchart TD
    classDef domain fill:#E1F5FE,stroke:#0288D1,stroke-width:2px;
    classDef stvn fill:#E8F8F5,stroke:#1ABC9C,stroke-width:2px;
    classDef facet fill:#FFF9C4,stroke:#FBC02D,stroke-width:2px;

    Sub["Domain Model Field"]:::domain --> Target{"Target STVN Type"}:::facet

    Target -- "Board Coordinates" --> C1["{ #unsigned #size 4 #minIncl 1 #maxExcl 9 } :Int"]:::stvn
    Target -- "Clock and Halfmoves" --> C2["{ #unsigned #size 7 #maxExcl 101 } :Int"]:::stvn
    Target -- "Turn Sequence" --> C3["{ #unsigned #size 10 #minIncl 1 } :Int"]:::stvn
    Target -- "Evaluation Score" --> C4["{ #size 16 } :Int"]:::stvn
    Target -- "Text Identifiers" --> C5["{ #minSize 1 #maxSize N } :String"]:::stvn
    Target -- "Square / Move / Turn" --> C6[":Tuple( ... ) with Nominal Brand"]:::stvn
```

### 2.1 Board Coordinates & Scalar Factorization

The schema declares base `:Int` and `:String` types paired with prefix metadata facets ordered strictly by the STVN 2.0.0 5-tier decimal sequence:

1. **Board Column File (`:File`):**
   ```stvn
   :File :Enum [ #A #B #C #D #E #F #G #H ]
   ```
2. **Board Row Rank (`:Rank`):**
   - Declares a 4-bit unsigned integer constrained to ranks 1 through 8.
   - Enforces half-open discrete interval governance: `#minIncl 1 #maxExcl 9`.
   - Prohibits `#maxIncl` and `#minExcl` (`E011` / `ERR_DISCRETE_BOUND_KIND_PROHIBITED`).
   ```stvn
   :Rank { #unsigned #size 4 #minIncl 1 #maxExcl 9 } :Int
   ```
3. **Board Square (`:Square`, `:AlgebraicSquare`, `:SquareIndex`):**
   - Defined as a nominal tuple pairing `:File` and `:Rank`, alongside algebraic coordinate regex validation and 6-bit linear indexing without redundant `W003` bounds:
   ```stvn
   :Square          :Tuple( :File :Rank )
   :AlgebraicSquare { #regex "^[a-h][1-8]$" } :String
   :SquareIndex     { #unsigned #size 6 } :Int
   ```

### 2.2 Move Tracking & Discrete Half-Open Intervals

Discrete numeric types reject closed upper bounds (`#maxIncl`) and open lower bounds (`#minExcl`). All integer ranges use half-open intervals $[minIncl, maxExcl)$:

1. **Fifty-Move Rule Counter (`:HalfmovesSincePawnOrCapture`):**
   - Tracks plies since last capture or pawn move ($0 \le h \le 100$).
   - Formulated as a 7-bit unsigned integer with half-open bound $[0, 101)$ and zero redundant `#minIncl 0`:
   ```stvn
   :HalfmovesSincePawnOrCapture { #unsigned #size 7 #maxExcl 101 } :Int
   ```
2. **Turn Sequence Number (`:TurnNumber`):**
   - Tracks positive 1-based turn numbers ($t \ge 1$).
   - Formulated as a 10-bit unsigned integer:
   ```stvn
   :TurnNumber { #unsigned #size 10 #minIncl 1 } :Int
   ```
3. **Engine Evaluation Score (`:CentipawnEvaluation`):**
   - Represents engine advantage in centipawns ($-32768 \le eval \le 32767$).
   - Formulated as a 16-bit signed integer:
   ```stvn
   :CentipawnEvaluation { #size 16 } :Int
   ```

### 2.3 String Cardinality Governance (MCT § 3.1.3)

Under MCT § 3.1.3, the `#size` facet represents physical memory bit-width and is prohibited on `:String`. Applying `#size` to `:String` emits `E008` / `ERR_STRING_CARDINALITY_PROHIBITED`. All string types express character capacity through logical `#minSize` and `#maxSize` bounds:

1. **Algebraic Move String (`:SanMoveString`):**
   ```stvn
   :SanMoveString { #minSize 1 #maxSize 8 } :String
   ```
2. **Match and Player Identifiers (`:MatchId`, `:PlayerName`):**
   ```stvn
   :MatchId    { #minSize 1 #maxSize 64 } :String
   :PlayerName { #minSize 1 #maxSize 64 } :String
   ```
3. **Board State String (`:FenStringFixed`, `:ForsythEdwardsNotation`):**
   ```stvn
   :FenStringFixed { #minSize 1 #maxSize 128 } :String
   :ForsythEdwardsNotation :FenStringFixed
   ```

---

## 3. The Anti-Fragile Nominal Pure-IR Invariant

### 3.1 Prohibition of Blind Positional Indexing

In Value-Oriented Programming (VOP), structural tuples must not be deserialized blindly. Code that accesses `tuple.elements().get(i)` without verifying element type and nominal schema creates fragile systems prone to `ClassCastException` and silent type confusion.

The repository establishes the **Anti-Fragile Nominal Pure-IR Invariant**:
- Every mapper, codec, and visitor must verify the nominal schema identity (`ast.schema().aliasName()`) before extracting fields.
- Mappers must verify that `ast.schema().aliasName()` matches expected nominal aliases (`:GameHistory`, `:TurnState`, `:Move`, `:Square`).
- Mappers must verify structural arity ($M == N$), assert typed child variants, and narrow `BigInteger` values strictly at the Java record perimeter via `intValueExact()` and `longValueExact()`.

### 3.2 Error Localization with `NominalSchemaMismatchException`

When a pure-IR `StvnValue` node fails nominal assertions, mappers must not crash with generic exceptions. Mappers must throw `NominalSchemaMismatchException`:

```java
package org.stvnadore.chess.codec;

public class NominalSchemaMismatchException extends RuntimeException {
  private final String expectedAlias;
  private final String actualAlias;
  private final String path;
  private final transient StvnValue offendingNode;
  ...
}
```

The exception pins:
1. **Expected Alias:** The nominal type expected by the domain model (e.g. `":Square"`).
2. **Actual Alias:** The alias found on the node, or `"<unnamed>"` if unbranded.
3. **Structural Path:** The exact path within the aggregate tree (e.g. `"turns[0].move.from"`).
4. **Offending Node:** The exact `StvnValue` pure-IR instance that triggered the failure.

---

## 4. Zero-Trust Client Strategy 0x7 Wire Protocol

The chess application uses Binary Encoding Strategy `0x7` (`ExplicitSha256`) for network replication, persistence, and inter-process communication, and Strategy `0x8` (`SelfDescribingSchema` `#DEFS`) for `.stvn_bd` binary schema publication.

```mermaid
flowchart TD
    classDef wire fill:#F3E5F5,stroke:#7B1FA2,stroke-width:2px;
    classDef crc fill:#FFF3E0,stroke:#F57C00,stroke-width:2px;
    classDef digest fill:#E8F5E9,stroke:#388E3C,stroke-width:2px;

    subgraph WireLayout["Binary Wire Frame Layout (Little-Endian)"]
        H1["Bytes 0-3: Magic 'STVN' (0x53 0x54 0x56 0x4E)"]:::wire
        H2["Byte 4: Control Byte 0x87 (Bit 7=CRC-32C, Bits 0-3=Strategy 0x7)"]:::wire
        H3["Byte 5: MetaControlByte 0x00 (#BODY)"]:::wire
        H4["Bytes 6-37: 32-Byte Root StvnSchema SHA-256 Digest (2e4b82fd...)"]:::digest
        H5["Bytes 38..N-4: Bit-Packed Pure-IR Payload Slices"]:::wire
        H6["Bytes N-4..N: 4-Byte IEEE 802.3 CRC-32C Trailer"]:::crc
    end
```

### 4.1 Byte 4 Control Byte Framing & Application Profile

While the core STVN 2.0.0 binary specification supports both trailerless (`Bit 7 = 0`) and trailer-framed (`Bit 7 = 1`) streams, the chess showcase application adopts a zero-trust profile by default, emitting Byte 4 as `0x87` (Strategy `0x7` with mandatory Castagnoli CRC-32C) to demonstrate end-to-end transport integrity verification.

Strategy `0x7` wire frames specify Byte 4 and the leading header slice as follows:
- **Bits 0–3 (`0x7`):** Strategy Identifier (`ExplicitSha256`).
- **Bit 7 (`0x80`):** CRC-32C Trailer Sentinel (`1 = Present`). When combined with Strategy `0x7`, Byte 4 evaluates to `0x87`.
- **Byte 5 (`0x00`):** `MetaControlByte` declaring payload identity without dynamic header metadata fields (`#BODY`).
- **Bytes 6–37:** The 32-byte binary SHA-256 wire digest of the resolved `:gaming/chess/GameHistory` root `StvnSchema` (`2e4b82fdf50c9245ecdbfda202af932dd86c8e1596c929f18c4760aa2698999a`).
- **Repository CAS Shard Digest (`.stvn_d` / `.stvn_bd`):** When `chess_turn.gaming.stvn_d` is flattened and published to `stvnadore-repository`, its 1,781-byte canonical `#DEFS` preimage hashes to `b5f53562247e08e093b75e795ea1f8e85d3c80cf297b4958f1fce718f07a5837` (`b5/b5f53562247e08e093b75e795ea1f8e85d3c80cf297b4958f1fce718f07a5837.stvn_cas.stvn`).

### 4.2 Zero-Trust Verification Pipeline

When reading an incoming binary stream, `ChessBinaryCodec` executes a four-stage zero-trust check across both `decode` and zero-copy `openRootTuple`:
1. **Magic Byte Verification:** Confirms Bytes 0–3 equal ASCII `'STVN'` (`0x53 0x54 0x56 0x4E`) in Network Byte Order.
2. **CRC-32C Integrity Verification:** Computes the CRC-32C checksum over Bytes $0 \dots N-4$. Compares the result against the final 4 bytes. Any mismatch throws `MalformedPayloadException`.
3. **Cryptographic Root Schema Hash Parity:** Extracts Bytes 6–37 (following Byte 5 `MetaControlByte`). Compares the digest against `StvnSchemaHasher.computeSha256(gameHistorySchema)`. A mismatch throws `PoisonedRegistryPayloadException`.
4. **Registry Fallback Resolution:** If the schema is not cached locally, queries `GET /api/v1/schemas/cas/{hash}` on `stvnadore-repository`.

---

## 5. Benchmarking Methodology & Quality Gates

The chess showcase establishes performance and quality baselines to ensure production readiness across high-throughput game simulations.

### 5.1 Throughput and Memory Baselines

`ChessWireBenchmarker` measures serialization efficiency across four wire layouts:
1. `STVN Binary (Strategy 0x87)`: Target size $\le 2{,}200\text{ bytes}$ per 100 plies ($\sim 81\%$ smaller than pretty JSON).
2. `JSON (Compact)`: $\sim 11{,}200\text{ bytes}$ per 100 plies.
3. `JSON (Pretty)`: $\sim 18{,}400\text{ bytes}$ per 100 plies.
4. `Raw Flat Binary`: $\sim 4{,}800\text{ bytes}$ per 100 plies.

**Performance Threshold:** `ChessBinaryCodec` must encode and decode at least $10{,}000\text{ turns/second}$ on a single virtual thread without heap allocations during flyweight inspection via `StvnTupleReader`.

### 5.2 Build Quality Gates

The build configuration in `pom.xml` enforces:
- **Compiler Baseline:** Java 21 LTS (`<release>21</release>`).
- **Strict Diagnostics:** `-Werror`, `-Xlint:all`, `-Xlint:-processing`.
- **Javadoc Lint Enforcement:** `<failOnError>true</failOnError>`, `<failOnWarnings>true</failOnWarnings>`, `<doclint>all</doclint>`.
- **Null Safety:** Strict JSpecify 1.0.0 `@NullMarked` enforcement across all domain and codec packages.
