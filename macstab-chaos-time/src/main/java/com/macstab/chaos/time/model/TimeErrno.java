/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

/**
 * POSIX errno values that libchaos-time can inject as the failure outcome of an intercepted
 * time-syscall.
 *
 * <p>This is a closed palette of the six errno names the libchaos-time C parser accepts (see
 * {@code chaos_time_config.c} §parse_errno). Surfacing arbitrary {@code int} codes would let users
 * construct rules the runtime silently rejects; modelling the palette as an enum makes invalid
 * choices unrepresentable at the call site.
 *
 * <p>This enum is intentionally separate from the other modules' errno palettes — time-syscall
 * errnos are a distinct namespace and only the six tokens below are recognised by libchaos-time.
 *
 * <p>The {@link #wireForm()} is the enum {@link #name()} verbatim — a Java rename forces a
 * compile-error cascade at every call site.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/TIME.md">libchaos-time
 *     rule grammar</a>
 */
public enum TimeErrno {

  /**
   * Resource temporarily unavailable — emitted when the rule budget is exhausted or the syscall
   * would block under load. Valid on every time selector.
   */
  EAGAIN,

  /**
   * Bad address — the supplied {@code struct timespec*} or buffer pointer was unreadable / not
   * writable. Valid on every time selector.
   */
  EFAULT,

  /** Interrupted by a signal. Particularly relevant for {@code nanosleep} and {@code usleep}. */
  EINTR,

  /** Invalid argument — bad {@code clockid_t} or out-of-range timespec. */
  EINVAL,

  /** Function not implemented — kernel / libc lacks the requested syscall. */
  ENOSYS,

  /** Operation not permitted — caller lacks the required capability for the chosen clock. */
  EPERM;

  /**
   * @return the enum constant name verbatim — uppercase POSIX-style token written to the
   *     libchaos-time config file
   */
  public String wireForm() {
    return name();
  }
}
