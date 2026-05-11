/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;

/**
 * Selector for a libchaos-dns rule — identifies the resolver call(s) that the rule applies to.
 *
 * <p>Sealed algebraic data type covering the seven selector forms in the libchaos-dns rule grammar:
 *
 * <pre>
 *   dns://api.example.com         ← {@link Forward.ExactHost}      forward exact-host match
 *   dns://*.example.com           ← {@link Forward.SuffixWildcard} forward suffix-wildcard match
 *   dns://*                       ← {@link Forward.AllForward}     forward wildcard
 *   rdns://1.2.3.4                ← {@link Reverse.Ipv4}           reverse IPv4 exact-address
 *   rdns://[::1]                  ← {@link Reverse.Ipv6}           reverse IPv6 exact-address
 *   rdns://*                      ← {@link Reverse.AllReverse}     reverse wildcard
 *   *                             ← {@link Wildcard}               every resolver call
 * </pre>
 *
 * <p><strong>Kind classification.</strong> Each variant has a {@link Kind} — {@code FORWARD},
 * {@code REVERSE}, or {@code ANY}. {@link DnsRule} uses it to enforce the effect-compatibility
 * matrix: forward-only effects ({@code OVERRIDE}, {@code FILTER_FAMILY}, {@code LIMIT}, {@code
 * SHUFFLE}) require a forward selector; reverse-only behaviours apply only to reverse selectors.
 *
 * <p><strong>Defensive validation</strong> applies at construction. Host strings and IP literals
 * are rejected if they contain newline or {@code ':'} characters that would corrupt the
 * line-oriented config grammar. Hostname length is bounded to the DNS limit (253 chars).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     rule grammar</a>
 */
public sealed interface DnsSelector
    permits DnsSelector.Forward, DnsSelector.Reverse, DnsSelector.Wildcard {

  /** Maximum DNS hostname length (RFC 1035). */
  int MAX_HOSTNAME_LENGTH = 253;

  /**
   * Selector kind — used by {@link DnsRule} to enforce effect compatibility.
   *
   * <ul>
   *   <li>{@code FORWARD} — selects {@code getaddrinfo()} calls; supports every effect
   *   <li>{@code REVERSE} — selects {@code getnameinfo()} calls; only EAI/LATENCY/REWRITE/SERVICE
   *   <li>{@code ANY} — wildcard matches both; only effects valid on both kinds are allowed
   * </ul>
   */
  enum Kind {
    /** Forward lookup ({@code getaddrinfo}). */
    FORWARD,
    /** Reverse lookup ({@code getnameinfo}). */
    REVERSE,
    /** Either kind. */
    ANY
  }

  /**
   * Renders this selector as the libchaos-dns wire token.
   *
   * @return non-null wire form, e.g. {@code "dns://api.example.com"}
   */
  String toSelector();

  /**
   * @return selector kind, used by {@link DnsRule} for effect compatibility
   */
  Kind kind();

  // ==================== Static factories ====================

  /**
   * Forward exact-host selector.
   *
   * @param name DNS host name (e.g. {@code "api.example.com"})
   * @return forward selector matching only this exact name
   */
  static DnsSelector host(final String name) {
    return new Forward.ExactHost(name);
  }

  /**
   * Forward suffix-wildcard selector.
   *
   * @param suffix host suffix without the leading {@code "*."} (e.g. {@code "example.com"}); {@link
   *     Forward.SuffixWildcard#toSelector()} adds the prefix on render
   * @return forward selector matching {@code "*." + suffix}
   */
  static DnsSelector suffix(final String suffix) {
    return new Forward.SuffixWildcard(suffix);
  }

  /**
   * @return forward wildcard selector matching every {@code getaddrinfo()} call
   */
  static DnsSelector anyForward() {
    return Forward.AllForward.ANY;
  }

  /**
   * Reverse IPv4 selector.
   *
   * @param address dotted-quad IPv4 literal (e.g. {@code "127.0.0.1"})
   * @return reverse selector matching this exact IPv4 address
   */
  static DnsSelector reverseIpv4(final String address) {
    return new Reverse.Ipv4(address);
  }

  /**
   * Reverse IPv6 selector.
   *
   * @param address IPv6 literal, unbracketed (e.g. {@code "::1"}); brackets are added on render
   * @return reverse selector matching this exact IPv6 address
   */
  static DnsSelector reverseIpv6(final String address) {
    return new Reverse.Ipv6(address);
  }

  /**
   * @return reverse wildcard selector matching every {@code getnameinfo()} call
   */
  static DnsSelector anyReverse() {
    return Reverse.AllReverse.ANY;
  }

  /**
   * @return any-resolver-call wildcard selector
   */
  static DnsSelector wildcard() {
    return Wildcard.ANY;
  }

  // ==================== Forward variants ====================

  /** Forward-lookup selectors — match against {@code getaddrinfo()} queries. */
  sealed interface Forward extends DnsSelector
      permits Forward.ExactHost, Forward.SuffixWildcard, Forward.AllForward {

    @Override
    default Kind kind() {
      return Kind.FORWARD;
    }

    /** Forward exact-host match. */
    record ExactHost(String name) implements Forward {
      public ExactHost {
        validateHost(name);
      }

      @Override
      public String toSelector() {
        return "dns://" + name;
      }
    }

    /**
     * Forward suffix-wildcard match. The suffix is stored without the {@code "*."} prefix; {@link
     * #toSelector()} adds it on render.
     */
    record SuffixWildcard(String suffix) implements Forward {
      public SuffixWildcard {
        Objects.requireNonNull(suffix, "suffix must not be null");
        if (suffix.isBlank()) {
          throw new IllegalArgumentException("suffix must not be blank");
        }
        if (suffix.startsWith(".") || suffix.startsWith("*")) {
          throw new IllegalArgumentException(
              "suffix must be stored without leading '.' or '*' (renderer adds '*.' prefix): "
                  + suffix);
        }
        validateHost(suffix);
      }

      @Override
      public String toSelector() {
        return "dns://*." + suffix;
      }
    }

    /** Forward wildcard — matches every {@code getaddrinfo()} call. */
    enum AllForward implements Forward {
      /** The single forward-wildcard instance. */
      ANY;

      @Override
      public String toSelector() {
        return "dns://*";
      }
    }
  }

  // ==================== Reverse variants ====================

  /** Reverse-lookup selectors — match against {@code getnameinfo()} queries. */
  sealed interface Reverse extends DnsSelector
      permits Reverse.Ipv4, Reverse.Ipv6, Reverse.AllReverse {

    @Override
    default Kind kind() {
      return Kind.REVERSE;
    }

    /** Reverse IPv4 exact-address match. */
    record Ipv4(String address) implements Reverse {
      public Ipv4 {
        validateIpv4(address);
      }

      @Override
      public String toSelector() {
        return "rdns://" + address;
      }
    }

    /**
     * Reverse IPv6 exact-address match. {@code address} is stored unbracketed; {@link
     * #toSelector()} adds the brackets.
     */
    record Ipv6(String address) implements Reverse {
      public Ipv6 {
        validateIpv6(address);
      }

      @Override
      public String toSelector() {
        return "rdns://[" + address + "]";
      }
    }

    /** Reverse wildcard — matches every {@code getnameinfo()} call. */
    enum AllReverse implements Reverse {
      /** The single reverse-wildcard instance. */
      ANY;

      @Override
      public String toSelector() {
        return "rdns://*";
      }
    }
  }

  // ==================== Any-kind wildcard ====================

  /** Any-resolver-call wildcard — matches every forward and reverse query. */
  enum Wildcard implements DnsSelector {
    /** The single any-kind wildcard instance. */
    ANY;

    @Override
    public Kind kind() {
      return Kind.ANY;
    }

    @Override
    public String toSelector() {
      return "*";
    }
  }

  // ==================== Validation helpers ====================

  private static void validateHost(final String host) {
    Objects.requireNonNull(host, "host must not be null");
    if (host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    if (host.length() > MAX_HOSTNAME_LENGTH) {
      throw new IllegalArgumentException(
          "host length "
              + host.length()
              + " exceeds DNS limit "
              + MAX_HOSTNAME_LENGTH
              + ": "
              + host);
    }
    if (host.indexOf('\n') >= 0 || host.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(
          "host must not contain newline characters (config-file injection guard)");
    }
    if (host.indexOf(':') >= 0) {
      throw new IllegalArgumentException(
          "host must not contain ':' (config-file field-separator collision): " + host);
    }
    if (host.indexOf(' ') >= 0) {
      throw new IllegalArgumentException("host must not contain whitespace: " + host);
    }
  }

  private static void validateIpv4(final String address) {
    Objects.requireNonNull(address, "address must not be null");
    final InetAddress parsed = parseLiteral(address);
    if (!(parsed instanceof Inet4Address)) {
      throw new IllegalArgumentException(
          "address must be a numeric IPv4 literal, got: '" + address + "' (parsed as IPv6)");
    }
  }

  private static void validateIpv6(final String address) {
    Objects.requireNonNull(address, "address must not be null");
    if (address.indexOf('[') >= 0 || address.indexOf(']') >= 0) {
      throw new IllegalArgumentException(
          "IPv6 address must be stored unbracketed (renderer adds brackets): " + address);
    }
    final InetAddress parsed = parseLiteral(address);
    if (!(parsed instanceof Inet6Address)) {
      throw new IllegalArgumentException(
          "address must be a numeric IPv6 literal, got: '" + address + "' (parsed as IPv4)");
    }
  }

  /**
   * Parse a textual IP literal strictly — never issues a DNS query.
   *
   * <p>{@link InetAddress#ofLiteral(String)} (JDK 22+) is the right tool here: it parses only
   * textual IP literals and throws {@link IllegalArgumentException} for hostnames, unlike {@link
   * InetAddress#getByName(String)} which falls through to a DNS query for non-literals.
   */
  private static InetAddress parseLiteral(final String address) {
    try {
      return InetAddress.ofLiteral(address);
    } catch (final IllegalArgumentException ex) {
      throw new IllegalArgumentException("address is not a valid IP literal: " + address, ex);
    }
  }
}
