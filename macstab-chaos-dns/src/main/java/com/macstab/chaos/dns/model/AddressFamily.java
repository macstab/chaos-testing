/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

/**
 * IP address family selector for libchaos-dns' {@code FILTER_FAMILY} effect.
 *
 * <p>libchaos-dns retains only result nodes whose {@code ai_family} matches the configured value
 * when {@code FILTER_FAMILY} is in effect. The library accepts the string tokens {@code "inet4"}
 * (or its alias {@code "ipv4"}) and {@code "inet6"} (alias {@code "ipv6"}) on the wire — numeric
 * {@code AF_*} constants are rejected by the parser.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     rule grammar</a>
 */
public enum AddressFamily {

  /** IPv4 — wire token {@code "inet4"}. */
  INET("inet4"),

  /** IPv6 — wire token {@code "inet6"}. */
  INET6("inet6");

  private final String wireForm;

  AddressFamily(final String wireForm) {
    this.wireForm = wireForm;
  }

  /**
   * The string token written to the libchaos-dns config file.
   *
   * @return {@code "inet4"} for {@link #INET}, {@code "inet6"} for {@link #INET6}
   */
  public String wireForm() {
    return wireForm;
  }
}
