/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.api.AdvancedConnectionChaos;
import com.macstab.chaos.connection.strategy.libchaos.LibchaosNetConnectionChaos;
import com.macstab.chaos.connection.strategy.toxiproxy.ToxiproxyConnectionChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.spi.ConnectionChaosStrategy;

import lombok.extern.slf4j.Slf4j;

/**
 * Connection-chaos facade that delegates to one or more {@link ConnectionChaosStrategy}
 * implementations.
 *
 * <p>Provides a unified API regardless of which underlying mechanism — Toxiproxy proxy,
 * libchaos-net syscall interpose, eBPF, etc. — actually produces or carries the fault. The
 * caller sees a single bucket: a toxic added by any strategy is removed by any cleanup call.
 *
 * <h2>Delegation policy</h2>
 *
 * <ul>
 *   <li><strong>Mutating add operations</strong> ({@link #addLatency}, {@link #dropPackets},
 *       {@link #limitBandwidth}, {@link #timeoutConnections}, {@link #slowClose}, {@link
 *       #rejectConnections}): <em>first-applicable wins, with capability fall-through</em>.
 *       Strategies are probed in declared order. The first strategy whose {@link
 *       ConnectionChaosStrategy#supports} returns {@code true} attempts the operation; if it
 *       raises {@link ChaosUnsupportedOperationException} (declaring it cannot model the verb),
 *       the next applicable strategy gets a chance. This is what lets a composite of
 *       {@code [LibchaosNetConnectionChaos, ToxiproxyConnectionChaos]} route every verb to
 *       libchaos-net <em>except</em> {@link #limitBandwidth} (which only Toxiproxy can model)
 *       transparently.
 *   <li><strong>Cleanup operations</strong> ({@link #removeToxic}, {@link #removeAllToxics},
 *       {@link #reset}): <em>fan-out, best-effort</em>. Every applicable strategy is invoked;
 *       per-strategy failures are collected. An aggregate {@link ChaosOperationFailedException}
 *       (with originals attached via {@link Throwable#addSuppressed}) is thrown only when
 *       <em>every</em> applicable strategy failed.
 *   <li>{@link #installTools} is intentionally a no-op. Both strategies install lazily on first
 *       use of a verb that needs them — eager fan-out would trigger Toxiproxy's binary fetch even
 *       in offline-CI runs that only use syscall-level chaos.
 * </ul>
 *
 * <h2>Capability tier</h2>
 *
 * <p>The composite exposes {@link #advanced()} returning the registered {@link
 * AdvancedConnectionChaos} strategy (typically {@link LibchaosNetConnectionChaos}). Calls on the
 * advanced API throw {@link com.macstab.chaos.core.exception.LibchaosNotPreparedException} when
 * the target container was not prepared with libchaos-net before {@code container.start()} —
 * loud and visible by design.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CompositeConnectionChaos implements ConnectionChaosStrategy {

  private final List<ConnectionChaosStrategy> strategies;
  private final AdvancedConnectionChaos advanced;

  /**
   * Creates a composite from the given strategies. Order is significant — strategies earlier in
   * the list are preferred for verbs both can model.
   *
   * @param strategies non-empty, non-null list of strategies (defensively copied)
   * @throws NullPointerException if {@code strategies} or any element is null
   * @throws IllegalArgumentException if {@code strategies} is empty
   */
  public CompositeConnectionChaos(final List<ConnectionChaosStrategy> strategies) {
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
            .filter(AdvancedConnectionChaos.class::isInstance)
            .map(AdvancedConnectionChaos.class::cast)
            .findFirst()
            .orElse(null);
  }

  /**
   * Convenience factory returning a composite with both backends, libchaos-net first. Portable
   * verbs route to libchaos-net by default; {@link #limitBandwidth} falls through to Toxiproxy.
   *
   * @return composite wrapping {@code [LibchaosNetConnectionChaos, ToxiproxyConnectionChaos]}
   */
  public static CompositeConnectionChaos standard() {
    return new CompositeConnectionChaos(
        List.of(new LibchaosNetConnectionChaos(), new ToxiproxyConnectionChaos()));
  }

  /**
   * Returns the syscall-level strategy registered with this composite.
   *
   * <p>Methods on the returned object require the container to have been prepared with
   * libchaos-net (i.e. {@code LibchaosTransport.prepare()} called before {@code
   * container.start()}). Invocations against an unprepared container raise {@link
   * com.macstab.chaos.core.exception.LibchaosNotPreparedException} — there is no silent
   * fallback.
   *
   * @return the advanced strategy
   * @throws ChaosOperationFailedException if no {@link AdvancedConnectionChaos} strategy was
   *     registered with this composite (e.g. the user constructed a Toxiproxy-only composite and
   *     then asked for advanced verbs)
   */
  public AdvancedConnectionChaos advanced() {
    if (advanced == null) {
      throw new ChaosOperationFailedException(
          "No syscall-level (AdvancedConnectionChaos) strategy is registered with this "
              + "composite. Use CompositeConnectionChaos.standard() or include a "
              + "LibchaosNetConnectionChaos in the constructor list to access advanced verbs.");
    }
    return advanced;
  }

  // ==================== ConnectionChaosStrategy probe ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final ConnectionChaosStrategy strategy : strategies) {
      if (safeSupports(strategy, container)) {
        return true;
      }
    }
    return false;
  }

  // ==================== ConnectionChaos add operations ====================

  @Override
  public void addLatency(
      final GenericContainer<?> container, final String target, final Duration latency) {
    delegateAdd(container, "addLatency", s -> s.addLatency(container, target, latency));
  }

  @Override
  public void dropPackets(
      final GenericContainer<?> container, final String target, final double rate) {
    delegateAdd(container, "dropPackets", s -> s.dropPackets(container, target, rate));
  }

  @Override
  public void limitBandwidth(
      final GenericContainer<?> container, final String target, final long bytesPerSecond) {
    delegateAdd(
        container, "limitBandwidth", s -> s.limitBandwidth(container, target, bytesPerSecond));
  }

  @Override
  public void timeoutConnections(
      final GenericContainer<?> container, final String target, final Duration timeout) {
    delegateAdd(
        container, "timeoutConnections", s -> s.timeoutConnections(container, target, timeout));
  }

  @Override
  public void slowClose(
      final GenericContainer<?> container, final String target, final Duration delay) {
    delegateAdd(container, "slowClose", s -> s.slowClose(container, target, delay));
  }

  @Override
  public void rejectConnections(final GenericContainer<?> container, final String target) {
    delegateAdd(
        container, "rejectConnections", s -> s.rejectConnections(container, target));
  }

  // ==================== Cleanup operations (fan-out) ====================

  @Override
  public void removeToxic(
      final GenericContainer<?> container, final String target, final String toxicName) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    fanOut(container, "removeToxic", s -> s.removeToxic(container, target, toxicName));
  }

  @Override
  public void removeAllToxics(final GenericContainer<?> container, final String target) {
    Objects.requireNonNull(target, "target must not be null");
    fanOut(container, "removeAllToxics", s -> s.removeAllToxics(container, target));
  }

  // ==================== ChaosProvider lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    // Intentional no-op: every strategy installs lazily on first use of a verb that needs it.
    // Eager fan-out here would trigger Toxiproxy's binary fetch in runs that only use
    // syscall-level chaos (offline-CI hostile).
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    fanOut(container, "reset", s -> s.reset(container));
  }

  @Override
  public boolean isSupported() {
    for (final ConnectionChaosStrategy strategy : strategies) {
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
    ChaosUnsupportedOperationException lastUnsupported = null;
    int applicable = 0;
    for (final ConnectionChaosStrategy strategy : strategies) {
      if (!safeSupports(strategy, container)) {
        continue;
      }
      applicable++;
      try {
        action.run(strategy);
        return;
      } catch (final ChaosUnsupportedOperationException e) {
        lastUnsupported = e;
        log.debug(
            "{}: strategy {} declared unsupported; trying next",
            operation,
            strategy.getClass().getSimpleName());
      }
    }
    if (applicable == 0) {
      throw new ChaosOperationFailedException(
          "No applicable connection-chaos strategy for "
              + operation
              + "; "
              + strategies.size()
              + " strategy/strategies registered, none reported supports()=true");
    }
    final ChaosOperationFailedException ex =
        new ChaosOperationFailedException(
            "Every applicable strategy declared " + operation + " unsupported");
    if (lastUnsupported != null) {
      ex.addSuppressed(lastUnsupported);
    }
    throw ex;
  }

  private void fanOut(
      final GenericContainer<?> container,
      final String operation,
      final StrategyAction action) {
    Objects.requireNonNull(container, "container must not be null");

    final List<Throwable> failures = new ArrayList<>();
    int succeeded = 0;
    int applicable = 0;
    for (final ConnectionChaosStrategy strategy : strategies) {
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
      final ConnectionChaosStrategy strategy, final GenericContainer<?> container) {
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
    void run(ConnectionChaosStrategy strategy);
  }
}
