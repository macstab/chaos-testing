/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.spi;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.FilesystemChaos;

/**
 * SPI for {@link FilesystemChaos} strategies that participate in a composite.
 *
 * <p>Each strategy represents a single filesystem-fault mechanism — shell commands ({@code dd},
 * {@code chmod}), libchaos-io syscall interpose, FUSE overlay, eBPF, etc. A composite fans cleanup
 * operations across every applicable strategy so callers see a single unified bucket: the mechanism
 * that <em>added</em> the fault is irrelevant to the caller, and any cleanup verb removes the fault
 * no matter which mechanism owns it.
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
 * @see FilesystemChaos
 */
public interface FilesystemChaosStrategy extends FilesystemChaos {

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
