/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

/**
 * POSIX {@code EAI_*} return codes that libchaos-dns can inject as the failure outcome of a
 * resolver call ({@code getaddrinfo} or {@code getnameinfo}).
 *
 * <p>This is a closed palette — only the codes libchaos-dns actually understands are listed.
 * Surfacing arbitrary {@code int} EAI codes would let users construct rules that the runtime
 * silently ignores; modelling the palette as an enum makes invalid choices unrepresentable at the
 * call site.
 *
 * <p>This enum is intentionally separate from the connection-module and filesystem-module errno
 * palettes — POSIX {@code EAI_*} codes are a distinct namespace from {@code errno(3)} values, and
 * cross-module type sharing would conflate them.
 *
 * <p>The {@link #wireForm()} is the enum {@link #name()} verbatim — a Java rename here forces a
 * compile-error cascade at every call site.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     rule grammar</a>
 */
public enum EaiErrno {

  /** Temporary failure in name resolution — caller should retry. */
  EAI_AGAIN,

  /** Non-recoverable failure in name resolution. */
  EAI_FAIL,

  /** Hostname or service name not known. */
  EAI_NONAME,

  /** Memory allocation failure inside the resolver. */
  EAI_MEMORY,

  /** System error (consult {@code errno} for further detail). */
  EAI_SYSTEM;

  /**
   * The token written to the libchaos-dns config file: the enum constant name verbatim.
   *
   * @return non-null, non-blank, uppercase POSIX-style token
   */
  public String wireForm() {
    return name();
  }
}
