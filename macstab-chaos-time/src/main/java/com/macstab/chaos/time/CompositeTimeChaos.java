/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.TimeChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.spi.TimeChaosStrategy;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.strategy.libchaos.LibchaosTimeChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * Time-chaos facade that delegates to one or more {@link TimeChaosStrategy} implementations.
 *
 * <p>Provides a unified API regardless of which underlying mechanism — libfaketime via {@code
 * LD_PRELOAD} timestamp-file ({@code shift}/{@code drift}), libchaos-time libc-symbol interpose of
 * {@code clock_gettime} / {@code nanosleep} / {@code usleep} (per-syscall errno injection +
 * per-clock {@code OFFSET}), kernel time namespaces, etc. — actually produces the fault.
 *
 * <h2>Delegation policy</h2>
 *
 * <ul>
 *   <li><strong>Whole-container verbs</strong> ({@link #shift}, {@link #drift}): first-applicable
 *       with capability fall-through. {@link LibchaosTimeChaos} declares both unsupported so the
 *       composite routes them cleanly to libfaketime.
 *   <li>{@link #reset}: fan-out across every applicable strategy, best-effort.
 *   <li>{@link #installTools}: fan-out (libfaketime installs the {@code faketime} package lazily;
 *       libchaos-time no-ops because preparation happens pre-start via {@code
 *       LibchaosTransport.prepare}).
 * </ul>
 *
 * <h2>Capability tier</h2>
 *
 * <p>{@link #advanced()} returns the registered {@link AdvancedTimeChaos} strategy (typically
 * {@link LibchaosTimeChaos}), which exposes typed VM-syscall-level verbs spanning {@code
 * clock_gettime}, {@code nanosleep}, and {@code usleep} — including the unique per-clock {@code
 * OFFSET} effect ({@link AdvancedTimeChaos#skewClock}). Advanced verbs require {@code
 * @SyscallLevelChaos(LibchaosLib.TIME)} on the test class — otherwise calls raise {@link
 * com.macstab.chaos.core.exception.LibchaosNotPreparedException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CompositeTimeChaos implements TimeChaosStrategy {

  private final List<TimeChaosStrategy> strategies;
  private final AdvancedTimeChaos advanced;

  /**
   * Creates a composite from the given strategies. Order is significant — strategies earlier in
   * the list are preferred for verbs both can model.
   *
   * @throws NullPointerException if {@code strategies} or any element is null
   * @throws IllegalArgumentException if {@code strategies} is empty
   */
  public CompositeTimeChaos(final List<TimeChaosStrategy> strategies) {
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
            .filter(AdvancedTimeChaos.class::isInstance)
            .map(AdvancedTimeChaos.class::cast)
            .findFirst()
            .orElse(null);
  }

  /**
   * Public no-arg constructor used by {@link java.util.ServiceLoader} to materialise the default
   * standard wiring.
   */
  public CompositeTimeChaos() {
    this(standardStrategies());
  }

  /**
   * Convenience factory returning a composite with both backends, libchaos-time first.
   *
   * @return composite wrapping {@code [LibchaosTimeChaos, LibfaketimeAdapter(LibfaketimeTimeChaos)]}
   */
  public static CompositeTimeChaos standard() {
    return new CompositeTimeChaos(standardStrategies());
  }

  private static List<TimeChaosStrategy> standardStrategies() {
    return List.of(
        new LibchaosTimeChaos(), new LibfaketimeTimeChaosAdapter(new LibfaketimeTimeChaos()));
  }

  /**
   * Returns the libc-symbol-level strategy registered with this composite.
   *
   * @throws ChaosOperationFailedException if no {@link AdvancedTimeChaos} strategy is registered
   */
  public AdvancedTimeChaos advanced() {
    if (advanced == null) {
      throw new ChaosOperationFailedException(
          "No libc-symbol-level (AdvancedTimeChaos) strategy is registered with this composite."
              + " Use CompositeTimeChaos.standard() or include a LibchaosTimeChaos in the"
              + " constructor list to access advanced verbs.");
    }
    return advanced;
  }

  // ==================== TimeChaosStrategy probe ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final TimeChaosStrategy strategy : strategies) {
      if (safeSupports(strategy, container)) {
        return true;
      }
    }
    return false;
  }

  // ==================== TimeChaos verbs (capability fall-through) ====================

  @Override
  public void shift(final GenericContainer<?> container, final Duration offset) {
    delegateMutating(container, "shift", s -> s.shift(container, offset));
  }

  @Override
  public void drift(final GenericContainer<?> container, final double speedMultiplier) {
    delegateMutating(container, "drift", s -> s.drift(container, speedMultiplier));
  }

  // ==================== ChaosProvider lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final TimeChaosStrategy strategy : strategies) {
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
    for (final TimeChaosStrategy strategy : strategies) {
      if (strategy.isSupported()) {
        return true;
      }
    }
    return false;
  }

  // ==================== Internal ====================

  private void delegateMutating(
      final GenericContainer<?> container, final String operation, final StrategyAction action) {
    Objects.requireNonNull(container, "container must not be null");
    ChaosUnsupportedOperationException lastUnsupported = null;
    int applicable = 0;
    for (final TimeChaosStrategy strategy : strategies) {
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
          "No applicable time-chaos strategy for "
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
      final GenericContainer<?> container, final String operation, final StrategyAction action) {
    Objects.requireNonNull(container, "container must not be null");

    final List<Throwable> failures = new ArrayList<>();
    int succeeded = 0;
    int applicable = 0;
    for (final TimeChaosStrategy strategy : strategies) {
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
      final TimeChaosStrategy strategy, final GenericContainer<?> container) {
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

  @FunctionalInterface
  private interface StrategyAction {
    void run(TimeChaosStrategy strategy);
  }

  /**
   * Adapter exposing the non-Strategy {@link LibfaketimeTimeChaos} as a {@link TimeChaosStrategy}.
   * The adapter is always considered to support a running container — libfaketime relies on
   * post-start exec via {@code printf > /tmp/faketime} rather than a label probe.
   */
  private static final class LibfaketimeTimeChaosAdapter implements TimeChaosStrategy {
    private final TimeChaos delegate;

    LibfaketimeTimeChaosAdapter(final TimeChaos delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public boolean supports(final GenericContainer<?> container) {
      Objects.requireNonNull(container, "container must not be null");
      try {
        return container.isRunning();
      } catch (final RuntimeException ignored) {
        return false;
      }
    }

    @Override
    public void shift(final GenericContainer<?> container, final Duration offset) {
      delegate.shift(container, offset);
    }

    @Override
    public void drift(final GenericContainer<?> container, final double speedMultiplier) {
      delegate.drift(container, speedMultiplier);
    }

    @Override
    public void installTools(final GenericContainer<?> container) {
      delegate.installTools(container);
    }

    @Override
    public void reset(final GenericContainer<?> container) {
      delegate.reset(container);
    }

    @Override
    public boolean isSupported() {
      return delegate.isSupported();
    }
  }
}
