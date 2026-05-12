/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Selector for a libchaos-process rule — identifies which process-lifecycle libc symbol the rule
 * applies to.
 *
 * <p>libchaos-process hooks seven POSIX entry points at the libc boundary plus a wildcard matching
 * every interposed call:
 *
 * <ul>
 *   <li>{@link #PTHREAD_CREATE} — thread spawn
 *   <li>{@link #FORK} — process fork
 *   <li>{@link #POSIX_SPAWN}, {@link #POSIX_SPAWNP} — spawn-from-path with/without PATH search
 *   <li>{@link #EXECVE}, {@link #EXECVEAT} — exec a binary
 *   <li>{@link #WAITPID} — wait for a child to change state
 *   <li>{@link #WILDCARD} — every interposed call
 * </ul>
 *
 * <p><strong>Errno × selector compatibility</strong> (validated at {@link ProcessRule}
 * construction):
 *
 * <table>
 *   <caption>Errnos accepted per selector — from POSIX.1-2017 and the Linux man-pages</caption>
 *   <tr>
 *     <th>Selector</th>
 *     <th>Valid errnos</th>
 *   </tr>
 *   <tr><td>{@link #PTHREAD_CREATE}</td><td>EAGAIN, EINVAL, EPERM</td></tr>
 *   <tr><td>{@link #FORK}</td><td>EAGAIN, ENOMEM</td></tr>
 *   <tr>
 *     <td>{@link #POSIX_SPAWN}, {@link #POSIX_SPAWNP}</td>
 *     <td>EAGAIN, EINVAL, ENOENT, ENOMEM</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #EXECVE}, {@link #EXECVEAT}</td>
 *     <td>EACCES, E2BIG, ELOOP, ENOEXEC, ENOENT, ENOMEM, EPERM, ETXTBSY</td>
 *   </tr>
 *   <tr><td>{@link #WAITPID}</td><td>ECHILD, EINTR, EINVAL</td></tr>
 *   <tr><td>{@link #WILDCARD}</td><td>union of all above (12 distinct errnos)</td></tr>
 * </table>
 *
 * <p><strong>Wildcard policy.</strong> Unlike libchaos-memory's strict-intersection wildcard, the
 * process-module wildcard accepts the <em>union</em> of all per-symbol errnos. This is a deliberate
 * departure: the strict intersection across the seven process symbols is empty (no single errno is
 * meaningful on all of {@code pthread_create}, {@code fork}, {@code execve}, and {@code waitpid}),
 * which would make {@code *:ERRNO:X} unusable. The wildcard's documented semantic is "fire on every
 * interposed call" — if the chosen errno is not meaningful for a particular symbol,
 * libchaos-process silently no-ops there, which matches the user-intended behaviour.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/PROCESS.md">libchaos-process
 *     rule grammar</a>
 */
public enum ProcessSelector {

  /** {@code pthread_create()} — thread creation (NPTL clone with CLONE_THREAD). */
  PTHREAD_CREATE,

  /** {@code fork()} — process creation (clone with SIGCHLD only). */
  FORK,

  /** {@code posix_spawn()} — spawn-from-path with explicit path. */
  POSIX_SPAWN,

  /** {@code posix_spawnp()} — spawn with {@code PATH} search. */
  POSIX_SPAWNP,

  /** {@code execve()} — replace process image. */
  EXECVE,

  /** {@code execveat()} — execve relative to a directory fd. */
  EXECVEAT,

  /** {@code waitpid()} — wait for a child to change state. */
  WAITPID,

  /** Wildcard — matches every interposed call. Use the union errno set. */
  WILDCARD;

  /**
   * @return the libchaos-process selector token (e.g. {@code "pthread_create"}, {@code "fork"},
   *     {@code "*"})
   */
  public String wireForm() {
    return this == WILDCARD ? "*" : name().toLowerCase(Locale.ROOT);
  }

  // ==================== Errno compatibility ====================

  private static final Set<ProcessErrno> PTHREAD_CREATE_ERRNOS =
      EnumSet.of(ProcessErrno.EAGAIN, ProcessErrno.EINVAL, ProcessErrno.EPERM);

  private static final Set<ProcessErrno> FORK_ERRNOS =
      EnumSet.of(ProcessErrno.EAGAIN, ProcessErrno.ENOMEM);

  private static final Set<ProcessErrno> POSIX_SPAWN_ERRNOS =
      EnumSet.of(
          ProcessErrno.EAGAIN, ProcessErrno.EINVAL, ProcessErrno.ENOENT, ProcessErrno.ENOMEM);

  private static final Set<ProcessErrno> EXECVE_ERRNOS =
      EnumSet.of(
          ProcessErrno.EACCES,
          ProcessErrno.E2BIG,
          ProcessErrno.ELOOP,
          ProcessErrno.ENOEXEC,
          ProcessErrno.ENOENT,
          ProcessErrno.ENOMEM,
          ProcessErrno.EPERM,
          ProcessErrno.ETXTBSY);

  private static final Set<ProcessErrno> WAITPID_ERRNOS =
      EnumSet.of(ProcessErrno.ECHILD, ProcessErrno.EINTR, ProcessErrno.EINVAL);

  /** Union of every per-symbol set above — all 12 errnos. */
  private static final Set<ProcessErrno> WILDCARD_ERRNOS = EnumSet.allOf(ProcessErrno.class);

  /**
   * @return the immutable set of {@link ProcessErrno}s that libchaos-process accepts for this
   *     selector
   */
  public Set<ProcessErrno> validErrnos() {
    return switch (this) {
      case PTHREAD_CREATE -> PTHREAD_CREATE_ERRNOS;
      case FORK -> FORK_ERRNOS;
      case POSIX_SPAWN, POSIX_SPAWNP -> POSIX_SPAWN_ERRNOS;
      case EXECVE, EXECVEAT -> EXECVE_ERRNOS;
      case WAITPID -> WAITPID_ERRNOS;
      case WILDCARD -> WILDCARD_ERRNOS;
    };
  }

  /**
   * @param errno errno to test
   * @return {@code true} iff {@code errno} is in {@link #validErrnos()} for this selector
   */
  public boolean accepts(final ProcessErrno errno) {
    return validErrnos().contains(errno);
  }
}
