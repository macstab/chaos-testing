/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.config;

import java.util.Objects;

import lombok.NonNull;

/**
 * TCP proxy configuration for transparent traffic interception.
 *
 * <h2>Transparent Proxy Architecture</h2>
 *
 * <pre>
 * Client connects to:        container-hostname:servicePort (thinks it's the real service)
 *                                     ↓
 * iptables PREROUTING redirects to:  localhost:proxyPort (Toxiproxy)
 *                                     ↓
 * Toxiproxy forwards to:             localhost:servicePort (real service)
 * </pre>
 *
 * <h2>Port Selection Guidelines</h2>
 *
 * <ul>
 *   <li><strong>servicePort:</strong> Real service port (6379 Redis, 5432 Postgres, 3306 MySQL)
 *   <li><strong>proxyPort:</strong> High port for Toxiproxy (convention: servicePort + 10000)
 *   <li>Both ports must be in range [1, 65535]
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * ProxyConfiguration config = new ProxyConfiguration("redis", 6379, 16379, hostname);
 * }</pre>
 *
 * @param proxyName       unique proxy identifier in Toxiproxy (must not be null or blank)
 * @param servicePort     real service port [1, 65535]
 * @param proxyPort       Toxiproxy listen port [1, 65535]
 * @param containerHostname container hostname for client connections (must not be null)
 * @author Christian Schnapka - Macstab GmbH
 */
public record ProxyConfiguration(
    @NonNull String proxyName,
    int servicePort,
    int proxyPort,
    @NonNull String containerHostname) {

  /**
   * Compact constructor — validates all fields.
   *
   * @throws NullPointerException if proxyName or containerHostname is null
   * @throws IllegalArgumentException if either port is outside [1, 65535]
   */
  public ProxyConfiguration {
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(containerHostname, "containerHostname must not be null");
    servicePort = validatePort(servicePort, "servicePort");
    proxyPort = validatePort(proxyPort, "proxyPort");
  }

  private static int validatePort(final int port, final String name) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          String.format("%s must be in range [1, 65535], got: %d", name, port));
    }
    return port;
  }
}
