/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.spi;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.TimeChaos;

/**
 * SPI for {@link TimeChaos} strategies that participate in a composite.
 *
 * <p>Each strategy represents a single time-fault mechanism — libfaketime via {@code LD_PRELOAD}
 * timestamp-file driven offsets, libchaos-time libc-symbol interpose of {@code clock_gettime} /
 * {@code nanosleep} / {@code usleep}, kernel time namespaces, etc. A composite routes verbs to the
 * first applicable strategy and fans cleanup across every applicable strategy.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #supports(GenericContainer)} must be cheap, side-effect free, and tolerant of
 *       repeated invocation. Probe failures must be swallowed and reported as {@code false}.
 *   <li>Behaviour must not depend on this strategy's position within a composite.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeChaos
 */
public interface TimeChaosStrategy extends TimeChaos {

  /**
   * Indicates whether this strategy is ready to act on the given container.
   *
   * @param container target container
   * @return {@code true} when this strategy can serve requests for {@code container}
   * @throws NullPointerException if {@code container} is null
   */
  boolean supports(GenericContainer<?> container);
}
