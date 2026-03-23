/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Cache chaos injection interface.
 *
 * <p>Cache failures using protocol-aware proxy (Redis/Memcached).
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
 * testImplementation("com.macstab.chaos:macstab-chaos-cache:1.0.0")
 * }</pre>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * // Force 30% cache misses
 * chaos.cache().injectMisses(container, "user:*", 0.3);
 *
 * // Slow cache responses
 * chaos.cache().slowResponse(container, Duration.ofMillis(200));
 *
 * // Multiple chaos simultaneously
 * chaos.cache()
 *   .slowResponse(container, Duration.ofMillis(100))
 *   .injectMisses(container, "session:*", 0.1);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface CacheChaos extends ChaosProvider {

  /**
   * Inject cache misses (return nil/null).
   *
   * <p>Forces cache to return "not found" for matching keys, simulating cache miss.
   *
   * @param container target container
   * @param keyPattern key pattern (e.g., "user:*" or "*" for all)
   * @param rate miss rate (0.0-1.0, e.g., 0.3 = 30% misses)
   */
  void injectMisses(GenericContainer<?> container, String keyPattern, double rate);

  /**
   * Slow cache responses.
   *
   * <p>Adds latency to all cache operations.
   *
   * @param container target container
   * @param delay response delay
   */
  void slowResponse(GenericContainer<?> container, Duration delay);

  /**
   * Force eviction (simulate memory pressure).
   *
   * <p>Evicts percentage of keys to simulate LRU eviction under memory pressure.
   *
   * @param container target container
   * @param percentage percentage of keys to evict (1-100)
   */
  void forceEviction(GenericContainer<?> container, int percentage);
}
