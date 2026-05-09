/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import java.util.Objects;

/**
 * Endpoint selector for a libchaos-net rule.
 *
 * <p>Sealed algebraic data type covering the seven selector forms in the libchaos-net rule grammar.
 * Each variant carries the minimum data needed to render its wire token via {@link #toSelector()}.
 *
 * <p><strong>Selector grammar (libchaos-net):</strong>
 *
 * <pre>
 *   tcp4://host:port            ← {@link Tcp4}
 *   tcp6://[host]:port          ← {@link Tcp6}     (host stored unbracketed; brackets added on render)
 *   udp4://host:port            ← {@link Udp4}
 *   udp6://[host]:port          ← {@link Udp6}
 *   unix:///path                ← {@link Unix}     (absolute paths only)
 *   dns://hostname              ← {@link Dns}
 *   *                           ← {@link Wildcard} (matches every socket)
 * </pre>
 *
 * <p><strong>Defensive validation</strong> applies at construction. Hosts and paths are rejected
 * if they contain newline characters — those would otherwise inject extra rules into the
 * libchaos-net config file, which is line-oriented.
 *
 * <p>Instantiate via the static factories ({@link #tcp4(String, int)}, {@link #dns(String)}, …)
 * for ergonomics, or directly via the nested records / enum.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/NETWORK.md">libchaos-net
 *     rule grammar</a>
 */
public sealed interface Endpoint
    permits Endpoint.Tcp4,
        Endpoint.Tcp6,
        Endpoint.Udp4,
        Endpoint.Udp6,
        Endpoint.Unix,
        Endpoint.Dns,
        Endpoint.Wildcard {

  /**
   * Renders this endpoint as the libchaos-net selector token.
   *
   * @return non-null wire form, e.g. {@code "tcp4://db.example.com:5432"}
   */
  String toSelector();

  // ==================== Static factories (ergonomic) ====================

  /**
   * @param host hostname or IPv4 literal
   * @param port port in {@code [1, 65535]}
   * @return TCP/IPv4 endpoint
   */
  static Endpoint tcp4(final String host, final int port) {
    return new Tcp4(host, port);
  }

  /**
   * @param host IPv6 literal, unbracketed (brackets are added on render)
   * @param port port in {@code [1, 65535]}
   * @return TCP/IPv6 endpoint
   */
  static Endpoint tcp6(final String host, final int port) {
    return new Tcp6(host, port);
  }

  /**
   * @param host hostname or IPv4 literal
   * @param port port in {@code [1, 65535]}
   * @return UDP/IPv4 endpoint
   */
  static Endpoint udp4(final String host, final int port) {
    return new Udp4(host, port);
  }

  /**
   * @param host IPv6 literal, unbracketed
   * @param port port in {@code [1, 65535]}
   * @return UDP/IPv6 endpoint
   */
  static Endpoint udp6(final String host, final int port) {
    return new Udp6(host, port);
  }

  /**
   * @param path absolute filesystem path to the unix-domain socket
   * @return Unix-socket endpoint
   */
  static Endpoint unix(final String path) {
    return new Unix(path);
  }

  /**
   * @param hostname DNS hostname intercepted at {@code getaddrinfo} resolution time
   * @return DNS-resolution endpoint
   */
  static Endpoint dns(final String hostname) {
    return new Dns(hostname);
  }

  /**
   * @return wildcard endpoint matching every socket
   */
  static Endpoint wildcard() {
    return Wildcard.ANY;
  }

  // ==================== Variants ====================

  /** TCP over IPv4. */
  record Tcp4(String host, int port) implements Endpoint {
    public Tcp4 {
      validateHost(host);
      validatePort(port);
    }

    @Override
    public String toSelector() {
      return "tcp4://" + host + ":" + port;
    }
  }

  /** TCP over IPv6. {@code host} is stored unbracketed; brackets are added by {@link #toSelector()}. */
  record Tcp6(String host, int port) implements Endpoint {
    public Tcp6 {
      validateHost(host);
      validatePort(port);
    }

    @Override
    public String toSelector() {
      return "tcp6://[" + host + "]:" + port;
    }
  }

  /** UDP over IPv4. */
  record Udp4(String host, int port) implements Endpoint {
    public Udp4 {
      validateHost(host);
      validatePort(port);
    }

    @Override
    public String toSelector() {
      return "udp4://" + host + ":" + port;
    }
  }

  /** UDP over IPv6. {@code host} is stored unbracketed; brackets are added by {@link #toSelector()}. */
  record Udp6(String host, int port) implements Endpoint {
    public Udp6 {
      validateHost(host);
      validatePort(port);
    }

    @Override
    public String toSelector() {
      return "udp6://[" + host + "]:" + port;
    }
  }

  /** Unix-domain socket. {@code path} must be absolute. */
  record Unix(String path) implements Endpoint {
    public Unix {
      Objects.requireNonNull(path, "path must not be null");
      if (path.isBlank()) {
        throw new IllegalArgumentException("path must not be blank");
      }
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("path must be absolute (start with '/'): " + path);
      }
      requireNoNewline(path, "path");
    }

    @Override
    public String toSelector() {
      return "unix://" + path;
    }
  }

  /** DNS hostname intercepted at {@code getaddrinfo} resolution time. */
  record Dns(String hostname) implements Endpoint {
    public Dns {
      validateHost(hostname);
    }

    @Override
    public String toSelector() {
      return "dns://" + hostname;
    }
  }

  /** Wildcard endpoint — matches every socket. */
  enum Wildcard implements Endpoint {
    /** The single wildcard instance. */
    ANY;

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
    requireNoNewline(host, "host");
  }

  private static void validatePort(final int port) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be in [1, 65535], got: " + port);
    }
  }

  private static void requireNoNewline(final String value, final String fieldName) {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(
          fieldName + " must not contain newline characters (config-file injection guard)");
    }
  }
}
