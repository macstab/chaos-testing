/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

/**
 * POSIX errno values that libchaos-io can inject as the failure outcome of an intercepted syscall.
 *
 * <p>This is a closed palette — only the errnos libchaos-io actually understands are listed.
 * Surfacing arbitrary {@code int} errno codes would let users construct rules that the runtime
 * silently ignores; modelling the palette as an enum makes invalid choices unrepresentable at the
 * call site.
 *
 * <p>This enum is intentionally separate from {@code com.macstab.chaos.connection.model.Errno} —
 * the two libchaos backends accept different errno palettes (file errnos vs network errnos), and
 * cross-module type sharing would create coupling neither module needs.
 *
 * <p>The {@link #wireForm()} is the enum {@link #name()} verbatim. POSIX errno tokens are uppercase
 * by convention; the Java enum constants match exactly, so a rename here forces a compile-error
 * cascade at every call site — no silent drift between Java identifier and libchaos-io token.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/IO.md">libchaos-io
 *     rule grammar</a>
 */
public enum Errno {

  /** Input/output error — generic disk failure. */
  EIO,

  /** No space left on device. */
  ENOSPC,

  /** Disk quota exceeded. */
  EDQUOT,

  /** Read-only filesystem. */
  EROFS,

  /** Permission denied. */
  EACCES,

  /** Per-process file descriptor limit reached. */
  EMFILE,

  /** System-wide file descriptor limit reached. */
  ENFILE,

  /** No such file or directory. */
  ENOENT;

  /**
   * The token written to the libchaos-io config file: the enum constant name verbatim.
   *
   * @return non-null, non-blank, uppercase POSIX-style token
   */
  public String wireForm() {
    return name();
  }
}
