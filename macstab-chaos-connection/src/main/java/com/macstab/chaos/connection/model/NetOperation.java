/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import java.util.Locale;

/**
 * Network syscall operations that libchaos-net can intercept.
 *
 * <p>Each value corresponds to a syscall family in the libchaos-net rule grammar. The {@link
 * #wireForm()} token is the lowercased enum {@link #name()} — keeping the constant name aligned
 * with the libchaos-net grammar means a Java-side rename forces a coordinated update at every call
 * site (compile error) and the wire token follows automatically. No drift is possible.
 *
 * <p><strong>Effect compatibility</strong> (validated at {@code NetRule} construction, not here):
 *
 * <ul>
 *   <li>{@code LATENCY} — valid on every operation
 *   <li>{@code ERRNO} — valid on every operation except {@link #POLL} (POLL uses {@code TIMEOUT}
 *       semantics instead)
 *   <li>{@code CORRUPT} — valid only on {@link #RECV}
 *   <li>{@code TIMEOUT} — valid only on {@link #POLL}
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/NETWORK.md">libchaos-net
 *     rule grammar</a>
 */
public enum NetOperation {

  /** {@code socket()} — descriptor allocation. */
  SOCKET,

  /** {@code bind()} — assign address to socket. */
  BIND,

  /** {@code listen()} — mark socket as passive (server-side). */
  LISTEN,

  /** {@code connect()} — initiate connection on an active socket. */
  CONNECT,

  /** {@code accept()} — accept incoming connection on a passive socket. */
  ACCEPT,

  /** {@code shutdown()} — half-close (read, write, or both directions). */
  SHUTDOWN,

  /** {@code send()} / {@code sendto()} / {@code sendmsg()} — outbound payload. */
  SEND,

  /** {@code recv()} / {@code recvfrom()} / {@code recvmsg()} — inbound payload. */
  RECV,

  /** {@code poll()} / {@code select()} / {@code epoll_wait()} — readiness wait. */
  POLL;

  /**
   * The token written to the libchaos-net config file: the lowercased enum name. {@link
   * Locale#ROOT} is used to make the transform locale-independent (defensive against the Turkish
   * dotted-i edge case if a future constant happens to contain {@code I}).
   *
   * @return non-null, non-blank, lowercase wire-form token
   */
  public String wireForm() {
    return name().toLowerCase(Locale.ROOT);
  }
}
