/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

/**
 * IP address family selector for libchaos-dns' {@code FILTER_FAMILY} effect.
 *
 * <p>libchaos-dns retains only result nodes whose {@code ai_family} matches the configured value
 * when {@code FILTER_FAMILY} is in effect. The numeric values match the Linux {@code
 * <sys/socket.h>} constants ({@code AF_INET=2}, {@code AF_INET6=10}) which is what the library
 * writes onto the wire.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     rule grammar</a>
 */
public enum AddressFamily {

  /** IPv4 ({@code AF_INET=2}). */
  INET(2),

  /** IPv6 ({@code AF_INET6=10} on Linux). */
  INET6(10);

  private final int linuxValue;

  AddressFamily(final int linuxValue) {
    this.linuxValue = linuxValue;
  }

  /**
   * The numeric Linux {@code AF_*} value written to the libchaos-dns config file.
   *
   * @return {@code 2} for {@link #INET}, {@code 10} for {@link #INET6}
   */
  public int wireValue() {
    return linuxValue;
  }
}
