/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.spi;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DnsChaos;

/**
 * SPI for {@link DnsChaos} strategies that participate in a composite.
 *
 * <p>Each strategy represents a single DNS-fault mechanism — iptables/{@code resolv.conf} tamper,
 * libchaos-dns {@code getaddrinfo} interpose, in-cluster DNS server replacement, etc. A composite
 * routes verbs to the first applicable strategy and fans cleanup operations across every applicable
 * strategy so callers see a single unified bucket.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #supports(GenericContainer)} must be cheap, side-effect free, and tolerant of
 *       repeated invocation. It is consulted on every delegated call. Probe failures must be
 *       swallowed and reported as {@code false}, never propagated.
 *   <li>Behaviour must not depend on this strategy's position within a composite.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see DnsChaos
 */
public interface DnsChaosStrategy extends DnsChaos {

  /**
   * Indicates whether this strategy is ready to act on the given container.
   *
   * <p>Composites consult this probe before every delegated call. Implementations must be
   * deterministic for a given container state and must not throw — uncertainty maps to {@code
   * false}.
   *
   * @param container target container
   * @return {@code true} when this strategy can serve requests for {@code container}
   * @throws NullPointerException if {@code container} is null
   */
  boolean supports(GenericContainer<?> container);
}
