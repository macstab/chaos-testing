/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.model;

import java.util.Objects;

import lombok.Value;

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
 * <h2>Port Explanation</h2>
 *
 * <ul>
 *   <li><strong>servicePort:</strong> Real service port (e.g., Redis 6379, Postgres 5432)
 *   <li><strong>proxyPort:</strong> Toxiproxy listen port (e.g., 16379 for Redis)
 *   <li><strong>containerHostname:</strong> Container hostname for client connections
 * </ul>
 *
 * <h2>Example: Redis Proxy</h2>
 *
 * <pre>{@code
 * // Create proxy for Redis
 * ProxyConfiguration config = ProxyConfiguration.builder()
 *     .proxyName("redis")
 *     .servicePort(6379)       // Real Redis port
 *     .proxyPort(16379)        // Toxiproxy intercept port
 *     .build();
 *
 * String hostname = chaos.createProxy(container, config);
 *
 * // Client MUST connect via hostname:servicePort
 * Jedis client = new Jedis(hostname, 6379);
 *
 * // Traffic flow:
 * // client → hostname:6379 → iptables → localhost:16379 (Toxiproxy) → localhost:6379 (Redis)
 * }</pre>
 *
 * <h2>Critical: Why Container Hostname Matters</h2>
 *
 * <p>Clients <strong>MUST</strong> connect via container hostname, not localhost:
 *
 * <ul>
 *   <li>✅ <code>new Jedis(hostname, 6379)</code> → iptables redirect works
 *   <li>❌ <code>new Jedis("localhost", 6379)</code> → bypasses proxy!
 * </ul>
 *
 * <h2>Port Selection Guidelines</h2>
 *
 * <ul>
 *   <li><strong>servicePort:</strong> Use real service port (6379 for Redis, 5432 for Postgres,
 *       3306 for MySQL)
 *   <li><strong>proxyPort:</strong> Use high port (16379, 15432, 13306) to avoid conflicts
 *   <li><strong>Convention:</strong> proxyPort = servicePort + 10000
 *   <li><strong>Range:</strong> Both ports must be in [1-65535]
 * </ul>
 *
 * <h2>Common Services</h2>
 *
 * <ul>
 *   <li><strong>Redis:</strong> servicePort=6379, proxyPort=16379
 *   <li><strong>Postgres:</strong> servicePort=5432, proxyPort=15432
 *   <li><strong>MySQL:</strong> servicePort=3306, proxyPort=13306
 *   <li><strong>MongoDB:</strong> servicePort=27017, proxyPort=37017
 * </ul>
 *
 * <h2>Common Mistakes</h2>
 *
 * <ul>
 *   <li>❌ Connecting to localhost instead of container hostname (bypasses proxy)
 *   <li>❌ Using same port for service and proxy (port conflict)
 *   <li>❌ Forgetting to get hostname from createProxy return value
 * </ul>
 *
 * @see
 *     com.macstab.chaos.proxy.ProxyChaosProvider#createProxy(org.testcontainers.containers.GenericContainer,
 *     String, int, int)
 * @author Christian Schnapka - Macstab GmbH
 */
@Value
public class ProxyConfiguration {
  String proxyName;
  int servicePort;
  int proxyPort;
  String containerHostname;

  public ProxyConfiguration(
      final String proxyName,
      final int servicePort,
      final int proxyPort,
      final String containerHostname) {

    this.proxyName = Objects.requireNonNull(proxyName, "proxyName must not be null");
    this.servicePort = validatePort(servicePort, "servicePort");
    this.proxyPort = validatePort(proxyPort, "proxyPort");
    this.containerHostname =
        Objects.requireNonNull(containerHostname, "containerHostname must not be null");
  }

  private static int validatePort(final int port, final String name) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          String.format("%s must be in range [1, 65535], got: %d", name, port));
    }
    return port;
  }
}
