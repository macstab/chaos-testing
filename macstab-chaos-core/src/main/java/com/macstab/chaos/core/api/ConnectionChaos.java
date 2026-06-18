/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Connection chaos injection interface.
 *
 * <p>Connection pool exhaustion and network failures using Toxiproxy.
 *
 * <p><strong>REQUIRES NET_ADMIN CAPABILITY</strong> for iptables redirect:
 *
 * <pre>
 * .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
 *     .withCapAdd(Capability.NET_ADMIN))
 * </pre>
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-connection:1.0.0")
 * }</pre>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * // Add latency to database connections
 * chaos.addLatency(container, "db:5432", Duration.ofMillis(500));
 *
 * // Drop 10% of packets
 * chaos.dropPackets(container, "redis:6379", 0.1);
 *
 * // Multiple toxics on same target (stack independently)
 * chaos.addLatency(container, "api:8080", Duration.ofMillis(200));
 * chaos.limitBandwidth(container, "api:8080", 1024);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ConnectionChaos extends ChaosProvider {

  /**
   * Add latency to connection target.
   *
   * <p>Delays all traffic to target by specified duration.
   *
   * @param container target container
   * @param target target host:port (e.g., "db:5432")
   * @param latency latency to add
   */
  void addLatency(GenericContainer<?> container, String target, Duration latency);

  /**
   * Drop packets (simulate packet loss).
   *
   * <p>Randomly drops packets to simulate unreliable network.
   *
   * @param container target container
   * @param target target host:port
   * @param rate drop rate (0.0-1.0, e.g., 0.1 = 10% loss)
   */
  void dropPackets(GenericContainer<?> container, String target, double rate);

  /**
   * Limit bandwidth (bytes per second).
   *
   * <p>Throttles connection speed to simulate slow network.
   *
   * @param container target container
   * @param target target host:port
   * @param bytesPerSecond bandwidth limit
   */
  void limitBandwidth(GenericContainer<?> container, String target, long bytesPerSecond);

  /**
   * Timeout connections after duration.
   *
   * <p>Closes connections after specified duration to simulate connection timeout.
   *
   * @param container target container
   * @param target target host:port
   * @param timeout timeout duration
   */
  void timeoutConnections(GenericContainer<?> container, String target, Duration timeout);

  /**
   * Slow connection close (delay FIN packet).
   *
   * <p>Delays closing connections to simulate hanging connections.
   *
   * @param container target container
   * @param target target host:port
   * @param delay close delay
   */
  void slowClose(GenericContainer<?> container, String target, Duration delay);

  /**
   * Reject new connections.
   *
   * <p>Refuses all new connections to simulate connection pool exhaustion.
   *
   * @param container target container
   * @param target target host:port
   */
  void rejectConnections(GenericContainer<?> container, String target);

  /**
   * Remove a single named fault from the target across every active mechanism.
   *
   * <p>Idempotent — silently no-op if no such fault exists. Implementations <strong>must</strong>
   * attempt cleanup on every backend they could have touched (Toxiproxy state, libchaos-net config,
   * iptables, …) so callers see one bucket regardless of which mechanism originally applied the
   * fault.
   *
   * @param container target container (must be running)
   * @param target target host:port
   * @param toxicName fault identifier (e.g. {@code "latency"}, {@code "down"})
   */
  void removeToxic(GenericContainer<?> container, String target, String toxicName);

  /**
   * Remove every fault associated with the target across every active mechanism.
   *
   * <p>Underlying proxies and redirects stay intact — only fault rules are cleared. Idempotent.
   *
   * @param container target container (must be running)
   * @param target target host:port
   */
  void removeAllToxics(GenericContainer<?> container, String target);
}
