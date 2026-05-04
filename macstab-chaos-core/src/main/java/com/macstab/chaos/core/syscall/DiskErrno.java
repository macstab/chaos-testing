/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

/**
 * Errno codes injectable by {@code libchaos-io}.
 *
 * <p>Each constant maps directly to the POSIX errno name that libchaos-io writes
 * into the rule config file.
 */
public enum DiskErrno {

  /** I/O error — generic hardware or device failure. */
  EIO,

  /** No space left on device — disk full simulation. */
  ENOSPC,

  /** Disk quota exceeded. */
  EDQUOT,

  /** Read-only filesystem — simulates mounted read-only. */
  EROFS,

  /** Permission denied. */
  EACCES,

  /** Too many open files in system. */
  EMFILE,

  /** Out of memory (mmap/fallocate paths). */
  ENOMEM,

  /** Resource temporarily unavailable (non-blocking I/O). */
  EAGAIN;

  /** Returns the config-file token expected by libchaos-io. */
  public String toLibchaosToken() {
    return name();
  }
}
