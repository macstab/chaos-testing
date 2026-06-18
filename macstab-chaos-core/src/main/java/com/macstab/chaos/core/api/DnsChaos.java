/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * DNS chaos injection interface.
 *
 * <p>DNS resolution failures and delays using {@code iptables} and {@code tc}.
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-dns:1.0.0")
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface DnsChaos extends ChaosProvider {

  /**
   * Block DNS resolution for specific hostname.
   *
   * @param container target container
   * @param hostname hostname to block (e.g., "redis.example.com")
   */
  void blockResolution(GenericContainer<?> container, String hostname);

  /**
   * Delay DNS responses.
   *
   * @param container target container
   * @param delay DNS query delay
   */
  void delayResolution(GenericContainer<?> container, Duration delay);
}
