/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Time chaos injection interface.
 *
 * <p>Clock skew and drift simulation using {@code libfaketime} or kernel time namespaces.
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-time:1.0.0")
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface TimeChaos extends ChaosProvider {

  /**
   * Shift container clock forward/backward.
   *
   * @param container target container
   * @param offset time offset (positive = future, negative = past)
   */
  void shift(GenericContainer<?> container, Duration offset);

  /**
   * Make container clock run faster/slower.
   *
   * @param container target container
   * @param speedMultiplier clock speed (1.5 = 1.5x faster, 0.5 = half speed)
   */
  void drift(GenericContainer<?> container, double speedMultiplier);
}
