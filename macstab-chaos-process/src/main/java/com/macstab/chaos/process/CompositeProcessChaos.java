/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.model.ProcessInfo;
import com.macstab.chaos.core.model.Signal;
import com.macstab.chaos.core.spi.ProcessChaosStrategy;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.strategy.cgroups.CgroupsProcessChaos;
import com.macstab.chaos.process.strategy.libchaos.LibchaosProcessChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * Process-chaos facade that delegates to one or more {@link ProcessChaosStrategy} implementations.
 *
 * <p>Provides a unified API regardless of which underlying mechanism — cgroups v2 ({@code
 * pids.max}) plus signals via {@code kill}, libchaos-process libc-symbol interpose, eBPF
 * tracepoints, etc. — actually produces the fault.
 *
 * <h2>Delegation policy</h2>
 *
 * <ul>
 *   <li><strong>Whole-container verbs</strong> ({@link #kill}, {@link #pause}, {@link
 *       #limitProcesses}, {@link #listProcesses}): first-applicable with capability fall-through.
 *       {@link LibchaosProcessChaos} declares all four unsupported so the composite routes them
 *       cleanly to {@link CgroupsProcessChaos}.
 *   <li>{@link #reset}: fan-out across every applicable strategy, best-effort.
 *   <li>{@link #installTools}: fan-out (cgroups installs procps lazily; libchaos-process no-ops
 *       because preparation happens pre-start via {@code LibchaosTransport.prepare}).
 * </ul>
 *
 * <h2>Capability tier</h2>
 *
 * <p>{@link #advanced()} returns the registered {@link AdvancedProcessChaos} strategy (typically
 * {@link LibchaosProcessChaos}), which exposes ~28 typed VM-syscall-level verbs spanning thread
 * creation, fork/spawn, exec, and wait — including the unique {@code FAIL_AFTER} counter ({@link
 * AdvancedProcessChaos#exhaustThreadPool}, {@link AdvancedProcessChaos#exhaustProcessLimit}).
 * Advanced verbs require {@code @SyscallLevelChaos(LibchaosLib.PROCESS)} on the test class —
 * otherwise calls raise {@link com.macstab.chaos.core.exception.LibchaosNotPreparedException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CompositeProcessChaos implements ProcessChaosStrategy {

  private final List<ProcessChaosStrategy> strategies;
  private final AdvancedProcessChaos advanced;

  /**
   * Creates a composite from the given strategies. Order is significant — strategies earlier in the
   * list are preferred for verbs both can model.
   *
   * @throws NullPointerException if {@code strategies} or any element is null
   * @throws IllegalArgumentException if {@code strategies} is empty
   */
  public CompositeProcessChaos(final List<ProcessChaosStrategy> strategies) {
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
            .filter(AdvancedProcessChaos.class::isInstance)
            .map(AdvancedProcessChaos.class::cast)
            .findFirst()
            .orElse(null);
  }

  /**
   * Convenience factory returning a composite with both backends, libchaos-process first.
   *
   * @return composite wrapping {@code [LibchaosProcessChaos, CgroupsProcessChaos]}
   */
  public static CompositeProcessChaos standard() {
    return new CompositeProcessChaos(
        List.of(new LibchaosProcessChaos(), new CgroupsProcessChaos()));
  }

  /**
   * Returns the libc-symbol-level strategy registered with this composite.
   *
   * @throws ChaosOperationFailedException if no {@link AdvancedProcessChaos} strategy is registered
   */
  public AdvancedProcessChaos advanced() {
    if (advanced == null) {
      throw new ChaosOperationFailedException(
          "No libc-symbol-level (AdvancedProcessChaos) strategy is registered with this composite."
              + " Use CompositeProcessChaos.standard() or include a LibchaosProcessChaos in the"
              + " constructor list to access advanced verbs.");
    }
    return advanced;
  }

  // ==================== ProcessChaosStrategy probe ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final ProcessChaosStrategy strategy : strategies) {
      if (safeSupports(strategy, container)) {
        return true;
      }
    }
    return false;
  }

  // ==================== ProcessChaos verbs (capability fall-through) ====================

  @Override
  public void kill(
      final GenericContainer<?> container, final String processName, final Signal signal) {
    delegateMutating(container, "kill", s -> s.kill(container, processName, signal));
  }

  @Override
  public void pause(
      final GenericContainer<?> container, final String processName, final Duration duration) {
    delegateMutating(container, "pause", s -> s.pause(container, processName, duration));
  }

  @Override
  public void limitProcesses(final GenericContainer<?> container, final int maxProcesses) {
    delegateMutating(container, "limitProcesses", s -> s.limitProcesses(container, maxProcesses));
  }

  @Override
  public List<ProcessInfo> listProcesses(final GenericContainer<?> container) {
    return delegateReading(container, "listProcesses", s -> s.listProcesses(container));
  }

  // ==================== ChaosProvider lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final ProcessChaosStrategy strategy : strategies) {
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
    for (final ProcessChaosStrategy strategy : strategies) {
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
    for (final ProcessChaosStrategy strategy : strategies) {
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
          "No applicable process-chaos strategy for "
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
    for (final ProcessChaosStrategy strategy : strategies) {
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
          "No applicable process-chaos strategy for " + operation);
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
    for (final ProcessChaosStrategy strategy : strategies) {
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
      final ProcessChaosStrategy strategy, final GenericContainer<?> container) {
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
    void run(ProcessChaosStrategy strategy);
  }

  @FunctionalInterface
  private interface StrategyReadAction<T> {
    T run(ProcessChaosStrategy strategy);
  }
}
