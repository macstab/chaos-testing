/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

/**
 * POSIX errno values that libchaos-memory can inject as the failure outcome of an intercepted VM
 * syscall.
 *
 * <p>This is a closed palette — only the errnos libchaos-memory understands across the four hooked
 * symbols ({@code mmap}, {@code munmap}, {@code mprotect}, {@code madvise}) are listed. Modelling
 * the palette as an enum makes invalid choices unrepresentable at the call site; pairing with
 * {@link MemorySelector#accepts(MmapErrno)} prevents users from constructing rules the library
 * would silently no-op on.
 *
 * <p>This enum is intentionally separate from the connection-module and filesystem-module errno
 * palettes — memory errnos are a distinct namespace per POSIX.1-2017.
 *
 * <p>The {@link #wireForm()} is the enum {@link #name()} verbatim — a Java rename forces a
 * compile-error cascade at every call site.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/MEMORY.md">libchaos-memory
 *     rule grammar</a>
 */
public enum MmapErrno {

  /** Permission denied — invalid prot/flags for the mapping. Valid on mmap/mprotect/madvise. */
  EACCES,

  /** Temporary failure / would block. Valid on mmap/madvise. */
  EAGAIN,

  /** Bad file descriptor. Valid on mmap/madvise (for fd-backed paths). */
  EBADF,

  /** Invalid argument — bad length/alignment/flags. Valid on every interposed call. */
  EINVAL,

  /** System-wide file-descriptor limit reached. Valid on mmap. */
  ENFILE,

  /** No such device. Valid on mmap. */
  ENODEV,

  /** Out of memory. Valid on mmap/mprotect/madvise — the canonical allocation-failure code. */
  ENOMEM,

  /** Value too large to fit in the addressable range. Valid on mmap. */
  EOVERFLOW,

  /** Operation not permitted. Valid on mmap/madvise. */
  EPERM,

  /** Text file busy — denying write mapping of a running executable. Valid on mmap. */
  ETXTBSY,

  /** I/O error. Valid on madvise (when reading the backing fd). */
  EIO;

  /**
   * @return the enum constant name verbatim — uppercase POSIX-style token written to the
   *     libchaos-memory config file
   */
  public String wireForm() {
    return name();
  }
}
