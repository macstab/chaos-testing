/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Network chaos injection interface.
 *
 * <p>Provides latency injection, packet loss, bandwidth throttling, and network partitions using
 * Linux Traffic Control ({@code tc}) and {@code iptables}.
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-network:1.0.0")
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface NetworkChaos extends ChaosProvider {

  /**
   * Inject fixed latency (delay).
   *
   * @param container target container
   * @param delay latency to add (e.g., Duration.ofMillis(100))
   */
  void injectLatency(GenericContainer<?> container, Duration delay);

  /**
   * Inject latency with jitter (variable delay).
   *
   * @param container target container
   * @param delay base latency
   * @param jitter jitter amount (random variation)
   */
  void injectLatencyWithJitter(GenericContainer<?> container, Duration delay, Duration jitter);

  /**
   * Inject packet loss (percentage).
   *
   * @param container target container
   * @param percentage packet loss percentage (0.0-1.0, e.g., 0.05 = 5%)
   */
  void injectPacketLoss(GenericContainer<?> container, double percentage);

  /**
   * Inject correlated packet loss (Gilbert-Elliott model).
   *
   * @param container target container
   * @param percentage base loss percentage
   * @param correlation correlation coefficient (0.0-1.0)
   */
  void injectCorrelatedPacketLoss(
      GenericContainer<?> container, double percentage, double correlation);

  /**
   * Limit bandwidth (rate limiting).
   *
   * @param container target container
   * @param rate bandwidth limit (e.g., "10mbit", "1gbit")
   */
  void limitBandwidth(GenericContainer<?> container, String rate);

  /**
   * Partition container from target (block all traffic).
   *
   * @param container source container
   * @param target target container to partition from
   */
  void partitionFrom(GenericContainer<?> container, GenericContainer<?> target);

  /**
   * Reset all network chaos (remove tc rules, iptables rules).
   *
   * @param container target container
   */
  @Override
  void reset(GenericContainer<?> container);
}
