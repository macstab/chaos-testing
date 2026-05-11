/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.spi.DnsChaosStrategy;
import com.macstab.chaos.dns.api.AdvancedDnsChaos;
import com.macstab.chaos.dns.strategy.iptables.IptablesDnsChaos;
import com.macstab.chaos.dns.strategy.libchaos.LibchaosDnsChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * DNS-chaos facade that delegates to one or more {@link DnsChaosStrategy} implementations.
 *
 * <p>Provides a unified API regardless of which underlying mechanism — iptables REJECT on port 53,
 * libchaos-dns {@code getaddrinfo()} interpose, in-cluster DNS replacement, etc. — actually
 * produces the fault.
 *
 * <h2>Delegation policy</h2>
 *
 * <ul>
 *   <li><strong>Mutating verbs</strong> ({@link #blockResolution}, {@link #delayResolution}):
 *       first-applicable wins. Strategies are probed in declared order; the first whose {@link
 *       DnsChaosStrategy#supports} returns {@code true} handles the call. libchaos-dns natively
 *       handles both portable verbs, so no {@link
 *       com.macstab.chaos.core.exception.ChaosUnsupportedOperationException} fall-through is needed
 *       for DNS — unlike the filesystem and connection composites.
 *   <li>{@link #reset}: fan-out across every applicable strategy, best-effort.
 *   <li>{@link #installTools}: fan-out (iptables strategy lazily installs CoreDNS; libchaos-dns
 *       no-ops because preparation already happened pre-start).
 * </ul>
 *
 * <h2>Capability tier</h2>
 *
 * <p>The composite exposes {@link #advanced()} returning the registered {@link AdvancedDnsChaos}
 * strategy (typically {@link LibchaosDnsChaos}). Advanced verbs require
 * {@code @SyscallLevelChaos(LibchaosLib.DNS)} on the test class — calls on an unprepared container
 * raise {@link com.macstab.chaos.core.exception.LibchaosNotPreparedException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CompositeDnsChaos implements DnsChaosStrategy {

  private final List<DnsChaosStrategy> strategies;
  private final AdvancedDnsChaos advanced;

  /**
   * Creates a composite from the given strategies. Order is significant — strategies earlier in the
   * list are preferred for verbs both can model.
   *
   * @param strategies non-empty, non-null list of strategies (defensively copied)
   * @throws NullPointerException if {@code strategies} or any element is null
   * @throws IllegalArgumentException if {@code strategies} is empty
   */
  public CompositeDnsChaos(final List<DnsChaosStrategy> strategies) {
    Objects.requireNonNull(strategies, "strategies must not be null");
    if (strategies.isEmpty()) {
      throw new IllegalArgumentException("strategies must not be empty");
    }
    for (int i = 0; i < strategies.size(); i++) {
      Objects.requireNonNull(strategies.get(i), "strategy at index " + i + " must not be null");
    }
    this.strategies = List.copyOf(strategies);
    this.advanced =
        this.strategies.stream()
            .filter(AdvancedDnsChaos.class::isInstance)
            .map(AdvancedDnsChaos.class::cast)
            .findFirst()
            .orElse(null);
  }

  /**
   * Convenience factory returning a composite with both backends, libchaos-dns first.
   *
   * @return composite wrapping {@code [LibchaosDnsChaos, IptablesDnsChaos]}
   */
  public static CompositeDnsChaos standard() {
    return new CompositeDnsChaos(List.of(new LibchaosDnsChaos(), new IptablesDnsChaos()));
  }

  /**
   * Returns the resolver-boundary strategy registered with this composite.
   *
   * @return the advanced strategy
   * @throws ChaosOperationFailedException if no {@link AdvancedDnsChaos} strategy was registered
   */
  public AdvancedDnsChaos advanced() {
    if (advanced == null) {
      throw new ChaosOperationFailedException(
          "No resolver-boundary (AdvancedDnsChaos) strategy is registered with this "
              + "composite. Use CompositeDnsChaos.standard() or include a LibchaosDnsChaos in "
              + "the constructor list to access advanced verbs.");
    }
    return advanced;
  }

  // ==================== DnsChaosStrategy probe ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final DnsChaosStrategy strategy : strategies) {
      if (safeSupports(strategy, container)) {
        return true;
      }
    }
    return false;
  }

  // ==================== DnsChaos verbs (first-applicable wins) ====================

  @Override
  public void blockResolution(final GenericContainer<?> container, final String hostname) {
    delegateAdd(container, "blockResolution", s -> s.blockResolution(container, hostname));
  }

  @Override
  public void delayResolution(final GenericContainer<?> container, final Duration delay) {
    delegateAdd(container, "delayResolution", s -> s.delayResolution(container, delay));
  }

  // ==================== ChaosProvider lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final DnsChaosStrategy strategy : strategies) {
      if (!safeSupports(strategy, container)) {
        continue;
      }
      try {
        strategy.installTools(container);
      } catch (final RuntimeException ex) {
        log.debug(
            "installTools: strategy {} failed; continuing",
            strategy.getClass().getSimpleName(),
            ex);
      }
    }
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    fanOut(container, "reset", s -> s.reset(container));
  }

  @Override
  public boolean isSupported() {
    for (final DnsChaosStrategy strategy : strategies) {
      if (strategy.isSupported()) {
        return true;
      }
    }
    return false;
  }

  // ==================== Internal ====================

  private void delegateAdd(
      final GenericContainer<?> container, final String operation, final StrategyAction action) {
    Objects.requireNonNull(container, "container must not be null");
    for (final DnsChaosStrategy strategy : strategies) {
      if (!safeSupports(strategy, container)) {
        continue;
      }
      action.run(strategy);
      return;
    }
    throw new ChaosOperationFailedException(
        "No applicable DNS-chaos strategy for "
            + operation
            + "; "
            + strategies.size()
            + " strategy/strategies registered, none reported supports()=true");
  }

  private void fanOut(
      final GenericContainer<?> container, final String operation, final StrategyAction action) {
    Objects.requireNonNull(container, "container must not be null");

    final List<Throwable> failures = new ArrayList<>();
    int succeeded = 0;
    int applicable = 0;
    for (final DnsChaosStrategy strategy : strategies) {
      if (!safeSupports(strategy, container)) {
        continue;
      }
      applicable++;
      try {
        action.run(strategy);
        succeeded++;
      } catch (final RuntimeException e) {
        failures.add(e);
      }
    }

    if (applicable == 0) {
      log.debug("{}: no applicable strategy; skipping", operation);
      return;
    }
    if (succeeded == 0) {
      final ChaosOperationFailedException aggregate =
          new ChaosOperationFailedException(
              operation
                  + " failed on every applicable strategy ("
                  + failures.size()
                  + " of "
                  + applicable
                  + ")");
      for (final Throwable t : failures) {
        aggregate.addSuppressed(t);
      }
      throw aggregate;
    }
    if (!failures.isEmpty()) {
      log.debug(
          "{}: {} of {} applicable strategies failed; {} succeeded",
          operation,
          failures.size(),
          applicable,
          succeeded);
    }
  }

  private static boolean safeSupports(
      final DnsChaosStrategy strategy, final GenericContainer<?> container) {
    try {
      return strategy.supports(container);
    } catch (final RuntimeException e) {
      log.debug(
          "supports() probe threw on strategy {}: {}",
          strategy.getClass().getSimpleName(),
          e.getMessage());
      return false;
    }
  }

  /** Lambda target for delegation/fan-out — keeps per-strategy error handling uniform. */
  @FunctionalInterface
  private interface StrategyAction {
    void run(DnsChaosStrategy strategy);
  }
}
