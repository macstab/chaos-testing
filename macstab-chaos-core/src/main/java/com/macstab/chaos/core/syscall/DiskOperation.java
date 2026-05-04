/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

/**
 * Syscall operations intercepted by {@code libchaos-io}.
 *
 * <p>Not all effects apply to all operations. Constraints:
 * <ul>
 *   <li>{@code TORN} — write-type operations only ({@link #WRITE}, {@link #PWRITE})
 *   <li>{@code CORRUPT} — read-type operations only ({@link #READ}, {@link #PREAD})
 *   <li>{@code ERRNO}, {@code LATENCY} — all operations
 * </ul>
 */
public enum DiskOperation {

  READ,
  WRITE,
  OPEN,
  CLOSE,
  FSYNC,
  FDATASYNC,
  PREAD,
  PWRITE;

  /** Returns the config-file token expected by libchaos-io. */
  public String toLibchaosToken() {
    return name().toLowerCase();
  }

  /** True if this operation can produce a torn (partial) write. */
  public boolean supportsTorn() {
    return this == WRITE || this == PWRITE;
  }

  /** True if this operation can produce a corrupt (bit-flipped) read. */
  public boolean supportsCorrupt() {
    return this == READ || this == PREAD;
  }
}
