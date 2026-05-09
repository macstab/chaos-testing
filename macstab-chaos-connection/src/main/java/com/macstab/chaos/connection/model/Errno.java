/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

/**
 * POSIX errno values that libchaos-net can inject as the failure outcome of an intercepted syscall.
 *
 * <p>This is a closed palette — only the errnos libchaos-net actually understands are listed.
 * Surfacing arbitrary {@code int} errno codes would let users construct rules that the runtime
 * silently ignores; modelling the palette as an enum makes invalid choices unrepresentable at the
 * call site.
 *
 * <p>The {@link #wireForm()} is the enum {@link #name()} verbatim. POSIX errno tokens are uppercase
 * by convention; the Java enum constants match exactly, so a rename here forces a compile-error
 * cascade at every call site — no silent drift between Java identifier and libchaos-net token.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/NETWORK.md">libchaos-net
 *     rule grammar</a>
 */
public enum Errno {

  /** Connection refused (TCP RST on connect). */
  ECONNREFUSED,

  /** Connection timed out. */
  ETIMEDOUT,

  /** Connection reset by peer (mid-stream). */
  ECONNRESET,

  /** No route to host. */
  EHOSTUNREACH,

  /** Network is unreachable. */
  ENETUNREACH,

  /** Address already in use ({@code bind} fault). */
  EADDRINUSE,

  /** Cannot assign requested address. */
  EADDRNOTAVAIL,

  /** Broken pipe (write to a closed peer). */
  EPIPE,

  /** Per-process file descriptor limit reached. */
  EMFILE,

  /** System-wide file descriptor limit reached. */
  ENFILE,

  /** Resource temporarily unavailable / would block. */
  EAGAIN;

  /**
   * The token written to the libchaos-net config file: the enum constant name verbatim.
   *
   * @return non-null, non-blank, uppercase POSIX-style token
   */
  public String wireForm() {
    return name();
  }
}
