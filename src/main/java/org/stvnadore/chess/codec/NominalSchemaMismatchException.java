package org.stvnadore.chess.codec;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.ir.StvnValue;

import java.util.Objects;

/**
 * Exception thrown when an STVN AST node fails nominal schema assertions during domain mapping.
 */
@NullMarked
public class NominalSchemaMismatchException extends RuntimeException {

  /** Serial version UID for serialization compatibility. */
  private static final long serialVersionUID = 1L;

  /** The expected nominal schema alias. */
  private final String expectedAlias;

  /** The actual nominal schema alias encountered. */
  private final String actualAlias;

  /** The AST path where the mismatch occurred. */
  private final String path;

  /** The offending AST node. */
  private final transient StvnValue offendingNode;

  /**
   * Constructs a new NominalSchemaMismatchException.
   *
   * @param expectedAlias the expected nominal schema alias
   * @param actualAlias   the actual nominal schema alias found on the node, or null
   * @param path          the hierarchical path within the AST structure
   * @param offendingNode the AST node that failed validation
   */
  public NominalSchemaMismatchException(String expectedAlias, @Nullable String actualAlias, String path, StvnValue offendingNode) {
    super(String.format("Nominal schema mismatch at '%s': expected '%s' but found '%s' on node %s",
        path, expectedAlias, actualAlias != null ? actualAlias : "<unnamed>", offendingNode));
    this.expectedAlias = Objects.requireNonNull(expectedAlias);
    this.actualAlias = actualAlias != null ? actualAlias : "<unnamed>";
    this.path = Objects.requireNonNull(path);
    this.offendingNode = Objects.requireNonNull(offendingNode);
  }

  /**
   * Returns the expected nominal schema alias.
   *
   * @return expected alias string
   */
  public String expectedAlias() {
    return expectedAlias;
  }

  /**
   * Returns the actual nominal schema alias encountered.
   *
   * @return actual alias string or &lt;unnamed&gt;
   */
  public String actualAlias() {
    return actualAlias;
  }

  /**
   * Returns the AST path where the mismatch occurred.
   *
   * @return path string
   */
  public String path() {
    return path;
  }

  /**
   * Returns the offending AST node.
   *
   * @return offending StvnValue node
   */
  public StvnValue offendingNode() {
    return offendingNode;
  }
}
