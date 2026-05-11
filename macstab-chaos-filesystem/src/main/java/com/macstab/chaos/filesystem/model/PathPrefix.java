/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import java.util.Objects;

/**
 * Path selector for a libchaos-io rule.
 *
 * <p>Sealed algebraic data type covering the two selector forms in the libchaos-io rule grammar:
 *
 * <pre>
 *   /absolute/path       ← {@link AbsolutePath}   matched longest-prefix-wins, path-boundary aware
 *   *                    ← {@link Wildcard}       lowest-priority fallback, matches everything
 * </pre>
 *
 * <p><strong>Matching semantics</strong> (from libchaos-io {@code IO.md} §13):
 *
 * <ul>
 *   <li>longest prefix wins
 *   <li>{@code *} is the lowest-priority wildcard
 *   <li>matching is path-boundary aware ({@code /data} does not match {@code /databases/...})
 *   <li>no globbing, no regex, no canonicalisation
 * </ul>
 *
 * <p><strong>Defensive validation</strong> applies at construction. Path strings are rejected if
 * they contain:
 *
 * <ul>
 *   <li>{@code \n} or {@code \r} — would inject extra rules into the line-oriented config file
 *   <li>{@code :} — clashes with the field separator in the libchaos-io grammar
 *   <li>{@code ..} segments — path traversal smell, refused outright
 * </ul>
 *
 * <p>Paths exceeding {@link #MAX_PATH_LENGTH} ({@value #MAX_PATH_LENGTH} bytes) are rejected to
 * match libchaos-io's {@code CHAOS_IO_MAX_RULE_PATH} bound; longer values would be truncated by the
 * library and produce surprising matches.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/IO.md">libchaos-io
 *     rule grammar</a>
 */
public sealed interface PathPrefix permits PathPrefix.AbsolutePath, PathPrefix.Wildcard {

  /** Maximum path length accepted by libchaos-io ({@code CHAOS_IO_MAX_RULE_PATH}). */
  int MAX_PATH_LENGTH = 256;

  /**
   * Renders this path prefix as the libchaos-io selector token.
   *
   * @return non-null wire form, e.g. {@code "/var/log"} or {@code "*"}
   */
  String toSelector();

  // ==================== Static factories ====================

  /**
   * @param path absolute filesystem path; must start with {@code /}
   * @return absolute-path selector
   */
  static PathPrefix path(final String path) {
    return new AbsolutePath(path);
  }

  /**
   * @return wildcard selector matching every path
   */
  static PathPrefix wildcard() {
    return Wildcard.ANY;
  }

  // ==================== Variants ====================

  /** Absolute filesystem path. Matching is longest-prefix-wins, path-boundary aware. */
  record AbsolutePath(String value) implements PathPrefix {
    public AbsolutePath {
      Objects.requireNonNull(value, "path must not be null");
      if (value.isBlank()) {
        throw new IllegalArgumentException("path must not be blank");
      }
      if (!value.startsWith("/")) {
        throw new IllegalArgumentException("path must be absolute (start with '/'): " + value);
      }
      if (value.length() > MAX_PATH_LENGTH) {
        throw new IllegalArgumentException(
            "path length "
                + value.length()
                + " exceeds libchaos-io limit "
                + MAX_PATH_LENGTH
                + ": "
                + value);
      }
      if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
        throw new IllegalArgumentException(
            "path must not contain newline characters (config-file injection guard)");
      }
      if (value.indexOf(':') >= 0) {
        throw new IllegalArgumentException(
            "path must not contain ':' (config-file field-separator collision): " + value);
      }
      if (containsTraversal(value)) {
        throw new IllegalArgumentException("path must not contain '..' segments: " + value);
      }
    }

    @Override
    public String toSelector() {
      return value;
    }

    private static boolean containsTraversal(final String path) {
      // Boundary-aware: '..' is forbidden as a segment, not as a substring.
      // 'a/../b' is rejected, 'a..b' is fine.
      int idx = 0;
      while ((idx = path.indexOf("..", idx)) >= 0) {
        final boolean leftBoundary = idx == 0 || path.charAt(idx - 1) == '/';
        final boolean rightBoundary = idx + 2 == path.length() || path.charAt(idx + 2) == '/';
        if (leftBoundary && rightBoundary) {
          return true;
        }
        idx += 2;
      }
      return false;
    }
  }

  /** Wildcard selector — matches every path. Lowest-priority match. */
  enum Wildcard implements PathPrefix {
    /** The single wildcard instance. */
    ANY;

    @Override
    public String toSelector() {
      return "*";
    }
  }
}
