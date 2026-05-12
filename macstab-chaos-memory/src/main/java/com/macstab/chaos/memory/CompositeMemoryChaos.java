/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.model.MemoryPressureInfo;
import com.macstab.chaos.core.spi.MemoryChaosStrategy;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.strategy.cgroups.CgroupsMemoryChaos;
import com.macstab.chaos.memory.strategy.libchaos.LibchaosMemoryChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * Memory-chaos facade that delegates to one or more {@link MemoryChaosStrategy} implementations.
 *
 * <p>Provides a unified API regardless of which underlying mechanism — cgroups v2 ({@code
 * memory.max} / {@code memory.high} / {@code memory.pressure}), libchaos-memory VM-syscall
 * interpose, eBPF, etc. — actually produces or carries the fault. The caller sees a single bucket:
 * a rule added by any strategy is removed by any cleanup call.
 *
 * <h2>Delegation policy</h2>
 *
 * <ul>
 *   <li><strong>Whole-container verbs</strong> ({@link #setLimit}, {@link #setPressure}, {@link
 *       #stress}, {@link #getCurrentUsage}, {@link #getPressure}): first-applicable with capability
 *       fall-through. {@link LibchaosMemoryChaos} declares all five unsupported (throws {@link
 *       ChaosUnsupportedOperationException}) so the composite cleanly routes them to {@link
 *       CgroupsMemoryChaos}. Read-style verbs ({@code getCurrentUsage}, {@code getPressure}) return
 *       the first applicable strategy's value.
 *   <li>{@link #reset}: fan-out across every applicable strategy, best-effort.
 *   <li>{@link #installTools}: fan-out (cgroups installs stress-ng lazily; libchaos-memory no-ops
 *       because preparation happens pre-start via {@code LibchaosTransport.prepare}).
 * </ul>
 *
 * <h2>Capability tier</h2>
 *
 * <p>{@link #advanced()} returns the registered {@link AdvancedMemoryChaos} strategy (typically
 * {@link LibchaosMemoryChaos}), which exposes ~25 typed VM-syscall-level verbs spanning heap,
 * file-mapping, threading, JIT, kernel hints, and cleanup paths. Advanced verbs require
 * {@code @SyscallLevelChaos(LibchaosLib.MEMORY)} on the test class — otherwise calls raise {@link
 * com.macstab.chaos.core.exception.LibchaosNotPreparedException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CompositeMemoryChaos implements MemoryChaosStrategy {

  private final List<MemoryChaosStrategy> strategies;
  private final AdvancedMemoryChaos advanced;

  /**
   * Creates a composite from the given strategies. Order is significant — strategies earlier in the
   * list are preferred for verbs both can model.
   *
   * @throws NullPointerException if {@code strategies} or any element is null
   * @throws IllegalArgumentException if {@code strategies} is empty
   */
  public CompositeMemoryChaos(final List<MemoryChaosStrategy> strategies) {
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
            .filter(AdvancedMemoryChaos.class::isInstance)
            .map(AdvancedMemoryChaos.class::cast)
            .findFirst()
            .orElse(null);
  }

  /**
   * Convenience factory returning a composite with both backends, libchaos-memory first.
   *
   * @return composite wrapping {@code [LibchaosMemoryChaos, CgroupsMemoryChaos]}
   */
  public static CompositeMemoryChaos standard() {
    return new CompositeMemoryChaos(List.of(new LibchaosMemoryChaos(), new CgroupsMemoryChaos()));
  }

  /**
   * Returns the VM-syscall-level strategy registered with this composite.
   *
   * @throws ChaosOperationFailedException if no {@link AdvancedMemoryChaos} strategy is registered
   */
  public AdvancedMemoryChaos advanced() {
    if (advanced == null) {
      throw new ChaosOperationFailedException(
          "No VM-syscall-level (AdvancedMemoryChaos) strategy is registered with this composite. "
              + "Use CompositeMemoryChaos.standard() or include a LibchaosMemoryChaos in the "
              + "constructor list to access advanced verbs.");
    }
    return advanced;
  }

  // ==================== MemoryChaosStrategy probe ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final MemoryChaosStrategy strategy : strategies) {
      if (safeSupports(strategy, container)) {
        return true;
      }
    }
    return false;
  }

  // ==================== MemoryChaos verbs (capability fall-through) ====================

  @Override
  public void setLimit(final GenericContainer<?> container, final String limit) {
    delegateMutating(container, "setLimit", s -> s.setLimit(container, limit));
  }

  @Override
  public void setPressure(final GenericContainer<?> container, final String threshold) {
    delegateMutating(container, "setPressure", s -> s.setPressure(container, threshold));
  }

  @Override
  public void stress(final GenericContainer<?> container, final String size) {
    delegateMutating(container, "stress", s -> s.stress(container, size));
  }

  @Override
  public long getCurrentUsage(final GenericContainer<?> container) {
    return delegateReading(container, "getCurrentUsage", s -> s.getCurrentUsage(container));
  }

  @Override
  public MemoryPressureInfo getPressure(final GenericContainer<?> container) {
    return delegateReading(container, "getPressure", s -> s.getPressure(container));
  }

  // ==================== ChaosProvider lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final MemoryChaosStrategy strategy : strategies) {
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
    for (final MemoryChaosStrategy strategy : strategies) {
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
    for (final MemoryChaosStrategy strategy : strategies) {
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
          "No applicable memory-chaos strategy for "
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

  private <T> T delegateReading(
      final GenericContainer<?> container,
      final String operation,
      final StrategyReadAction<T> action) {
    Objects.requireNonNull(container, "container must not be null");
    ChaosUnsupportedOperationException lastUnsupported = null;
    int applicable = 0;
    for (final MemoryChaosStrategy strategy : strategies) {
      if (!safeSupports(strategy, container)) {
        continue;
      }
      applicable++;
      try {
        return action.run(strategy);
      } catch (final ChaosUnsupportedOperationException e) {
        lastUnsupported = e;
      }
    }
    if (applicable == 0) {
      throw new ChaosOperationFailedException(
          "No applicable memory-chaos strategy for " + operation);
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
    for (final MemoryChaosStrategy strategy : strategies) {
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
      final MemoryChaosStrategy strategy, final GenericContainer<?> container) {
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
    void run(MemoryChaosStrategy strategy);
  }

  @FunctionalInterface
  private interface StrategyReadAction<T> {
    T run(MemoryChaosStrategy strategy);
  }
}
