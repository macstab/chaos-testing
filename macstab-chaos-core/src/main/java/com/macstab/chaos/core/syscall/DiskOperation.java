/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

/**
 * Syscall operations intercepted by {@code libchaos-io}.
 *
 * <p>Not all effects apply to all operations. Constraints:
 *
 * <ul>
 *   <li>{@code TORN} — write-type operations only ({@link #WRITE}, {@link #PWRITE})
 *   <li>{@code CORRUPT} — read-type operations only ({@link #READ}, {@link #PREAD})
 *   <li>{@code ERRNO}, {@code LATENCY} — all operations
 * </ul>
 */
public enum DiskOperation {

  /** Standard {@code read(2)} syscall. */
  READ,

  /** Standard {@code write(2)} syscall. */
  WRITE,

  /** {@code open(2)} / {@code openat(2)} syscall. */
  OPEN,

  /** {@code close(2)} syscall. */
  CLOSE,

  /** {@code fsync(2)} — flush data and metadata to storage. */
  FSYNC,

  /** {@code fdatasync(2)} — flush data only, skipping metadata. */
  FDATASYNC,

  /** {@code pread(2)} — positional read without seeking. */
  PREAD,

  /** {@code pwrite(2)} — positional write without seeking. */
  PWRITE;

  /**
   * Returns the config-file token expected by libchaos-io.
   *
   * @return lowercase syscall name, e.g. {@code "read"} or {@code "fsync"}
   */
  public String toLibchaosToken() {
    return name().toLowerCase();
  }

  /**
   * Returns {@code true} if this operation can produce a torn (partial) write.
   *
   * @return {@code true} for {@link #WRITE} and {@link #PWRITE}
   */
  public boolean supportsTorn() {
    return this == WRITE || this == PWRITE;
  }

  /**
   * Returns {@code true} if this operation can produce a corrupt (bit-flipped) read.
   *
   * @return {@code true} for {@link #READ} and {@link #PREAD}
   */
  public boolean supportsCorrupt() {
    return this == READ || this == PREAD;
  }
}
