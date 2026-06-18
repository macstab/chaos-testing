/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import java.util.Locale;

/**
 * Filesystem syscall operations that libchaos-io can intercept.
 *
 * <p>Each value corresponds to a logical operation in the libchaos-io rule grammar; one logical
 * operation may map to several libc entry points (e.g. {@link #WRITE} covers {@code write()},
 * {@code writev()}, {@code sendfile()}, and {@code copy_file_range()}).
 *
 * <p>The {@link #wireForm()} token is the lowercased enum {@link #name()} — keeping the constant
 * name aligned with the libchaos-io grammar means a Java-side rename forces a coordinated update at
 * every call site (compile error) and the wire token follows automatically. No drift is possible.
 *
 * <p><strong>Effect compatibility</strong> (validated at {@link IoRule} construction, not here):
 *
 * <ul>
 *   <li>{@code ERRNO} — valid on every operation
 *   <li>{@code LATENCY} — valid on every operation
 *   <li>{@code TORN} — valid only on {@link #WRITE} and {@link #PWRITE}
 *   <li>{@code CORRUPT} — valid only on {@link #READ} and {@link #PREAD}
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/IO.md">libchaos-io
 *     rule grammar</a>
 */
public enum IoOperation {

  /** {@code open()} / {@code openat()} — path-first descriptor allocation. */
  OPEN,

  /** {@code read()} / {@code readv()} — descriptor-based payload read. */
  READ,

  /** {@code write()} / {@code writev()} / {@code sendfile()} / {@code copy_file_range()}. */
  WRITE,

  /** {@code close()} — descriptor release. */
  CLOSE,

  /** {@code fsync()} — full file durability barrier. */
  FSYNC,

  /** {@code fdatasync()} — data-only durability barrier. */
  FDATASYNC,

  /** {@code pread()} / {@code preadv()} — positioned descriptor read. */
  PREAD,

  /** {@code pwrite()} / {@code pwritev()} — positioned descriptor write. */
  PWRITE,

  /** {@code ftruncate()} — change descriptor's file length. */
  TRUNCATE,

  /** {@code fallocate()} — pre-allocate or punch holes in descriptor's backing storage. */
  ALLOCATE,

  /** {@code unlinkat()} — remove a directory entry. */
  UNLINK,

  /** {@code renameat()} source-path matching. */
  RENAME_FROM,

  /** {@code renameat()} destination-path matching. */
  RENAME_TO;

  /**
   * The token written to the libchaos-io config file: the lowercased enum name. {@link Locale#ROOT}
   * keeps the transform locale-independent (defensive against the Turkish dotted-i edge case if a
   * future constant happens to contain {@code I}).
   *
   * <p>{@link #RENAME_FROM} → {@code "rename_from"}, {@link #RENAME_TO} → {@code "rename_to"} — the
   * underscore is preserved by {@code toLowerCase}, matching the libchaos-io grammar.
   *
   * @return non-null, non-blank, lowercase wire-form token
   */
  public String wireForm() {
    return name().toLowerCase(Locale.ROOT);
  }
}
