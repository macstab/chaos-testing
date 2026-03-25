/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Universal TCP proxy chaos for application-level fault injection.
 *
 * <p>Uses Toxiproxy to inject application-aware chaos into any TCP service without modifying the
 * service itself. Supports latency, timeouts, bandwidth limits, connection issues, and more.
 *
 * <p><strong>Architecture:</strong> Uses iptables PREROUTING to transparently redirect traffic.
 * Clients must connect via container hostname (not localhost) to hit the proxy.
 *
 * <p><strong>Use cases:</strong>
 *
 * <ul>
 *   <li>Cache misses (timeouts with probability)
 *   <li>Slow database queries
 *   <li>Partial HTTP responses
 *   <li>Connection hangs
 *   <li>Bandwidth throttling
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ProxyChaos {

  /**
   * Create a transparent proxy for a TCP service.
   *
   * <p>Sets up iptables PREROUTING redirect and starts Toxiproxy listening on proxyPort, forwarding
   * to localhost:servicePort.
   *
   * <p><strong>IMPORTANT:</strong> Clients must connect using the returned hostname (not localhost)
   * to hit the proxy.
   *
   * @param container container running the service
   * @param proxyName unique proxy name (used for toxic management)
   * @param servicePort original service port (e.g., 6379 for Redis, 5432 for PostgreSQL)
   * @param proxyPort port for Toxiproxy to listen on (e.g., 16379)
   * @return hostname to use for client connections
   */
  String createProxy(
      GenericContainer<?> container, String proxyName, int servicePort, int proxyPort);

  /**
   * Add latency to all traffic through the proxy.
   *
   * @param container container
   * @param proxyName proxy name
   * @param latency latency to add
   */
  void addLatency(GenericContainer<?> container, String proxyName, Duration latency);

  /**
   * Add connection timeouts with probability (simulates service unavailability).
   *
   * @param container container
   * @param proxyName proxy name
   * @param timeout timeout duration (0 = instant close)
   * @param probability probability of timeout (0.0-1.0)
   */
  void addTimeout(
      GenericContainer<?> container, String proxyName, Duration timeout, double probability);

  /**
   * Limit bandwidth through the proxy.
   *
   * @param container container
   * @param proxyName proxy name
   * @param rateKBps bandwidth limit in KB/s
   */
  void limitBandwidth(GenericContainer<?> container, String proxyName, long rateKBps);

  /**
   * Slow connection close (connection hangs on close).
   *
   * @param container container
   * @param proxyName proxy name
   * @param delay delay before closing
   */
  void slowClose(GenericContainer<?> container, String proxyName, Duration delay);

  /**
   * Remove all toxics and cleanup iptables rules. Stops Toxiproxy.
   *
   * @param container container
   */
  void reset(GenericContainer<?> container);

  /**
   * Check if proxy chaos is supported on this container.
   *
   * @return true if supported
   */
  boolean isSupported();
}
