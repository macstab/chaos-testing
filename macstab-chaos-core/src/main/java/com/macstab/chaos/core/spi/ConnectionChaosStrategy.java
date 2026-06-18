/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.spi;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ConnectionChaos;

/**
 * SPI for {@link ConnectionChaos} strategies that participate in a composite.
 *
 * <p>Each strategy represents a single connection-fault mechanism — Toxiproxy proxy plus iptables
 * redirect, libchaos-net syscall interpose, eBPF, etc. A composite fans cleanup operations across
 * every applicable strategy so callers see a single unified bucket: the mechanism that
 * <em>added</em> the fault is irrelevant to the caller, and any cleanup verb removes the fault no
 * matter which mechanism owns it.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #supports(GenericContainer)} must be cheap, side-effect free, and tolerant of
 *       repeated invocation. It is consulted on every delegated call. Probe failures must be
 *       swallowed and reported as {@code false}, never propagated.
 *   <li>{@link #removeToxic} and {@link #removeAllToxics} must be idempotent — a missing toxic is
 *       not an error. Implementations should log at {@code DEBUG} and return.
 *   <li>Behaviour must not depend on this strategy's position within a composite.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ConnectionChaos
 */
public interface ConnectionChaosStrategy extends ConnectionChaos {

  /**
   * Indicates whether this strategy is ready to act on the given container.
   *
   * <p>Composites consult this probe before every delegated call. Implementations must be
   * deterministic for a given container state and must not throw — uncertainty maps to {@code
   * false}.
   *
   * <p>{@link ConnectionChaos#removeToxic} and {@link ConnectionChaos#removeAllToxics} are
   * inherited from the user-facing interface — strategies must honour the same idempotent,
   * scoped-to-this-backend contract documented there.
   *
   * @param container target container
   * @return {@code true} when this strategy can serve requests for {@code container}
   * @throws NullPointerException if {@code container} is null
   */
  boolean supports(GenericContainer<?> container);
}
