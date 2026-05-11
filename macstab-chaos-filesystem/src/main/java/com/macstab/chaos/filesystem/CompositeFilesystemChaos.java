/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.spi.FilesystemChaosStrategy;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.strategy.libchaos.LibchaosIoFilesystemChaos;
import com.macstab.chaos.filesystem.strategy.shell.ShellFilesystemChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * Filesystem-chaos facade that delegates to one or more {@link FilesystemChaosStrategy}
 * implementations.
 *
 * <p>Provides a unified API regardless of which underlying mechanism — shell commands ({@code dd},
 * {@code chmod}), libchaos-io syscall interpose, FUSE overlay, eBPF, etc. — actually produces the
 * fault.
 *
 * <h2>Delegation policy</h2>
 *
 * <ul>
 *   <li><strong>Mutating add operations</strong> ({@link #fillDisk}, {@link
 *       #injectPermissionErrors}): <em>first-applicable wins, with capability fall-through</em>.
 *       Strategies are probed in declared order. The first strategy whose {@link
 *       FilesystemChaosStrategy#supports} returns {@code true} attempts the operation; if it raises
 *       {@link ChaosUnsupportedOperationException} (declaring it cannot model the verb), the next
 *       applicable strategy gets a chance. This is what lets a composite of {@code
 *       [LibchaosIoFilesystemChaos, ShellFilesystemChaos]} route {@code fillDisk} to the shell
 *       (which can {@code dd} a real file) while the libchaos-io strategy quietly stays out of the
 *       way — and vice versa for the advanced syscall-level verbs.
 *   <li>{@link #reset}: <em>fan-out, best-effort</em>. Every applicable strategy is invoked;
 *       per-strategy failures are collected. An aggregate {@link ChaosOperationFailedException}
 *       (with originals attached via {@link Throwable#addSuppressed}) is thrown only when
 *       <em>every</em> applicable strategy failed.
 *   <li>{@link #installTools} is intentionally a no-op. Both strategies install lazily: the shell
 *       strategy uses standard tools already present in every container, and the libchaos-io
 *       strategy is wired pre-start via {@code LibchaosTransport.prepare()}.
 * </ul>
 *
 * <h2>Capability tier</h2>
 *
 * <p>The composite exposes {@link #advanced()} returning the registered {@link
 * AdvancedFilesystemChaos} strategy (typically {@link LibchaosIoFilesystemChaos}). Calls on the
 * advanced API throw {@link com.macstab.chaos.core.exception.LibchaosNotPreparedException} when the
 * target container was not prepared with libchaos-io before {@code container.start()} — loud and
 * visible by design.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CompositeFilesystemChaos implements FilesystemChaosStrategy {

  private final List<FilesystemChaosStrategy> strategies;
  private final AdvancedFilesystemChaos advanced;

  /**
   * Creates a composite from the given strategies. Order is significant — strategies earlier in the
   * list are preferred for verbs both can model.
   *
   * @param strategies non-empty, non-null list of strategies (defensively copied)
   * @throws NullPointerException if {@code strategies} or any element is null
   * @throws IllegalArgumentException if {@code strategies} is empty
   */
  public CompositeFilesystemChaos(final List<FilesystemChaosStrategy> strategies) {
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
            .filter(AdvancedFilesystemChaos.class::isInstance)
            .map(AdvancedFilesystemChaos.class::cast)
            .findFirst()
            .orElse(null);
  }

  /**
   * Convenience factory returning a composite with both backends, libchaos-io first. Per-path
   * syscall-level verbs route to libchaos-io by default; {@link #fillDisk} and {@link
   * #injectPermissionErrors} fall through to the shell strategy.
   *
   * @return composite wrapping {@code [LibchaosIoFilesystemChaos, ShellFilesystemChaos]}
   */
  public static CompositeFilesystemChaos standard() {
    return new CompositeFilesystemChaos(
        List.of(new LibchaosIoFilesystemChaos(), new ShellFilesystemChaos()));
  }

  /**
   * Returns the syscall-level strategy registered with this composite.
   *
   * <p>Methods on the returned object require the container to have been prepared with libchaos-io
   * (i.e. {@code LibchaosTransport.prepare()} called before {@code container.start()}). Invocations
   * against an unprepared container raise {@link
   * com.macstab.chaos.core.exception.LibchaosNotPreparedException} — there is no silent fallback.
   *
   * @return the advanced strategy
   * @throws ChaosOperationFailedException if no {@link AdvancedFilesystemChaos} strategy was
   *     registered with this composite
   */
  public AdvancedFilesystemChaos advanced() {
    if (advanced == null) {
      throw new ChaosOperationFailedException(
          "No syscall-level (AdvancedFilesystemChaos) strategy is registered with this "
              + "composite. Use CompositeFilesystemChaos.standard() or include a "
              + "LibchaosIoFilesystemChaos in the constructor list to access advanced verbs.");
    }
    return advanced;
  }

  // ==================== FilesystemChaosStrategy probe ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    for (final FilesystemChaosStrategy strategy : strategies) {
      if (safeSupports(strategy, container)) {
        return true;
      }
    }
    return false;
  }

  // ==================== FilesystemChaos verbs (capability fall-through) ====================

  @Override
  public void fillDisk(final GenericContainer<?> container, final String size) {
    delegateAdd(container, "fillDisk", s -> s.fillDisk(container, size));
  }

  @Override
  public void injectPermissionErrors(
      final GenericContainer<?> container, final String path, final double rate) {
    delegateAdd(
        container, "injectPermissionErrors", s -> s.injectPermissionErrors(container, path, rate));
  }

  // ==================== ChaosProvider lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    // Intentional no-op: shell strategy uses standard tools (dd, chmod, rm) that are present in
    // every container; libchaos-io is wired pre-start via LibchaosTransport.prepare().
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    fanOut(container, "reset", s -> s.reset(container));
  }

  @Override
  public boolean isSupported() {
    for (final FilesystemChaosStrategy strategy : strategies) {
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
    for (final FilesystemChaosStrategy strategy : strategies) {
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
          "No applicable filesystem-chaos strategy for "
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
    for (final FilesystemChaosStrategy strategy : strategies) {
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
      final FilesystemChaosStrategy strategy, final GenericContainer<?> container) {
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
    void run(FilesystemChaosStrategy strategy);
  }
}
