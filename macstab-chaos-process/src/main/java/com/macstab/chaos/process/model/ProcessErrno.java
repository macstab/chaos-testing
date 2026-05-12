/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

/**
 * POSIX errno values that libchaos-process can inject as the failure outcome of an intercepted
 * process-lifecycle syscall.
 *
 * <p>This is a closed palette spanning the union of all errnos that the seven hooked symbols
 * ({@code pthread_create}, {@code fork}, {@code posix_spawn}, {@code posix_spawnp}, {@code execve},
 * {@code execveat}, {@code waitpid}) accept. Surfacing arbitrary {@code int} codes would let users
 * construct rules the runtime silently no-ops on; modelling the palette as an enum makes invalid
 * choices unrepresentable at the call site.
 *
 * <p>Per-symbol acceptance is enforced by {@link ProcessSelector#accepts(ProcessErrno)} and
 * validated at {@link ProcessRule} construction time.
 *
 * <p>This enum is intentionally separate from the other modules' errno palettes — process-
 * lifecycle errnos are a distinct namespace.
 *
 * <p>The {@link #wireForm()} is the enum {@link #name()} verbatim — a Java rename forces a
 * compile-error cascade at every call site.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/PROCESS.md">libchaos-process
 *     rule grammar</a>
 */
public enum ProcessErrno {

  /**
   * Resource temporarily unavailable — RLIMIT_NPROC reached, thread limit hit. Valid on
   * pthread_create / fork / posix_spawn(p).
   */
  EAGAIN,

  /**
   * Invalid argument — bad attr, bad waitpid options. Valid on pthread_create / posix_spawn(p) /
   * waitpid.
   */
  EINVAL,

  /**
   * Operation not permitted — e.g. no CAP_SYS_ADMIN for realtime scheduling. Valid on
   * pthread_create / execve.
   */
  EPERM,

  /**
   * Out of memory — kernel allocation failure during clone/exec. Valid on fork / posix_spawn(p) /
   * execve.
   */
  ENOMEM,

  /** No such file or directory — exec target missing. Valid on posix_spawn(p) / execve(at). */
  ENOENT,

  /** Permission denied — noexec mount, mode bits. Valid on execve(at). */
  EACCES,

  /** Bad ELF magic / not an executable format. Valid on execve(at). */
  ENOEXEC,

  /** argv+envp too large. Valid on execve(at). */
  E2BIG,

  /** Text file busy — binary is open for write. Valid on execve(at). */
  ETXTBSY,

  /** Symlink loop in interpreter resolution. Valid on execve(at). */
  ELOOP,

  /** No children to wait for. Valid on waitpid. */
  ECHILD,

  /** Interrupted by a signal (SA_RESTART bypassed). Valid on waitpid. */
  EINTR;

  /**
   * @return the enum constant name verbatim — uppercase POSIX-style token written to the
   *     libchaos-process config file
   */
  public String wireForm() {
    return name();
  }
}
