/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack.composers;

import com.macstab.chaos.connection.model.Endpoint;

/**
 * Resolves the {@code endpoint} attribute of connection L2 annotations to a libchaos-net
 * {@link Endpoint}.
 *
 * <p>Accepted forms:
 * <ul>
 *   <li>{@code "*"} — wildcard; matches every socket (default for all scenarios)</li>
 *   <li>{@code "tcp4://host:port"} — TCP/IPv4 to a specific host and port</li>
 *   <li>{@code "tcp6://[host]:port"} — TCP/IPv6; host unbracketed, brackets added on render</li>
 *   <li>{@code "udp4://host:port"} — UDP/IPv4</li>
 *   <li>{@code "udp6://[host]:port"} — UDP/IPv6</li>
 *   <li>{@code "unix:///path"} — Unix-domain socket (absolute path)</li>
 *   <li>{@code "dns://hostname"} — DNS interception at {@code getaddrinfo} resolution time</li>
 *   <li>{@code "hostname"} — bare hostname; shorthand for {@code dns://hostname}</li>
 * </ul>
 */
final class EndpointHelper {

  private EndpointHelper() {}

  static Endpoint resolve(final String spec) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException("endpoint must not be blank — use \"*\" for wildcard");
    }
    if ("*".equals(spec)) {
      return Endpoint.wildcard();
    }
    if (spec.startsWith("tcp4://")) {
      return parseTcp4(spec.substring(7));
    }
    if (spec.startsWith("tcp6://")) {
      return parseTcp6(spec.substring(7));
    }
    if (spec.startsWith("udp4://")) {
      return parseUdp4(spec.substring(7));
    }
    if (spec.startsWith("udp6://")) {
      return parseUdp6(spec.substring(7));
    }
    if (spec.startsWith("unix://")) {
      return Endpoint.unix(spec.substring(7));
    }
    if (spec.startsWith("dns://")) {
      return Endpoint.dns(spec.substring(6));
    }
    return Endpoint.dns(spec);
  }

  private static Endpoint parseTcp4(final String hostPort) {
    final int colon = hostPort.lastIndexOf(':');
    if (colon < 0) {
      throw new IllegalArgumentException(
          "tcp4:// endpoint requires a port — use \"tcp4://host:port\", got: tcp4://" + hostPort);
    }
    return Endpoint.tcp4(hostPort.substring(0, colon), parsePort(hostPort.substring(colon + 1)));
  }

  private static Endpoint parseTcp6(final String bracketed) {
    return Endpoint.tcp6(ipv6Host(bracketed), ipv6Port(bracketed));
  }

  private static Endpoint parseUdp4(final String hostPort) {
    final int colon = hostPort.lastIndexOf(':');
    if (colon < 0) {
      throw new IllegalArgumentException(
          "udp4:// endpoint requires a port — use \"udp4://host:port\", got: udp4://" + hostPort);
    }
    return Endpoint.udp4(hostPort.substring(0, colon), parsePort(hostPort.substring(colon + 1)));
  }

  private static Endpoint parseUdp6(final String bracketed) {
    return Endpoint.udp6(ipv6Host(bracketed), ipv6Port(bracketed));
  }

  private static String ipv6Host(final String bracketed) {
    final int open = bracketed.indexOf('[');
    final int close = bracketed.indexOf(']');
    if (open != 0 || close < 0) {
      throw new IllegalArgumentException(
          "IPv6 endpoint host must be bracketed — use \"tcp6://[host]:port\", got: " + bracketed);
    }
    return bracketed.substring(1, close);
  }

  private static int ipv6Port(final String bracketed) {
    final int close = bracketed.indexOf(']');
    final String afterBracket = bracketed.substring(close + 1);
    if (!afterBracket.startsWith(":")) {
      throw new IllegalArgumentException(
          "IPv6 endpoint requires a port after ']' — use \"tcp6://[host]:port\", got: "
              + bracketed);
    }
    return parsePort(afterBracket.substring(1));
  }

  private static int parsePort(final String s) {
    try {
      return Integer.parseInt(s);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("endpoint port is not a valid integer: \"" + s + "\"");
    }
  }
}
