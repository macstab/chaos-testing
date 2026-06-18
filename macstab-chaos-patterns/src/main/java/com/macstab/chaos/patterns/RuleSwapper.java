/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Adapter that turns a stateful "apply-rule / remove-previous-rule" pair into a stateless {@link
 * ValueConsumer}. Bridges any {@link ChaosPattern} to any chaos backend that exposes an
 * apply/remove lifecycle on a rule handle — connection, process, time, memory, dns, filesystem.
 *
 * <p><strong>Why this exists.</strong> A pattern emits a stream of values (probabilities,
 * latencies, counts, …) and a chaos backend's rule grammar is static at apply-time. Driving a chaos
 * backend from a pattern therefore requires re-applying the rule on every sample, removing the
 * previous apply to avoid handle leaks. This class encapsulates the {@code
 * AtomicReference<H>}-based swap so user code stays a one-liner.
 *
 * <p><strong>Semantics:</strong>
 *
 * <ul>
 *   <li>The new rule is applied <em>before</em> the old rule is removed — minimising the gap during
 *       which neither rule is active. If apply throws, the old rule stays in place.
 *   <li>The previous handle reference is updated atomically via {@link AtomicReference#getAndSet},
 *       so concurrent samples (from a parallel {@link PatternExecutor}) don't drop or double-remove
 *       handles.
 *   <li>The first sample has no previous handle to remove; subsequent samples remove the prior
 *       handle exactly once.
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * builder.rampFrom(0.0).to(1.0).over(Duration.ofSeconds(60))
 *     .execute(RuleSwapper.swap(
 *         p -> chaos.connection().failConnect(container, "redis-master:6379", p),
 *         h -> chaos.connection().remove(container, h)));
 * }</pre>
 *
 * <p><strong>Standalone.</strong> This class has no dependency on chaos-core or any chaos backend —
 * the {@code apply} and {@code remove} lambdas carry all backend-specific knowledge, keeping the
 * patterns module self-contained.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RuleSwapper {

  private RuleSwapper() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Build a {@link ValueConsumer} that, on every sample, applies a fresh rule and removes the
   * previously-applied handle.
   *
   * @param apply lambda that applies a rule for the given pattern value and returns the rule handle
   *     (e.g. a {@code RuleHandle} from any chaos module)
   * @param remove lambda that removes a previously-applied rule given its handle
   * @param <H> rule handle type (e.g. {@code RuleHandle})
   * @param <V> pattern value type (e.g. {@code Double} for probability, {@code Duration} for
   *     latency)
   * @return a {@link ValueConsumer} suitable for {@link ChaosPattern#applyTo}
   * @throws NullPointerException if {@code apply} or {@code remove} is null
   */
  public static <H, V> ValueConsumer<V> swap(final Function<V, H> apply, final Consumer<H> remove) {
    Objects.requireNonNull(apply, "apply must not be null");
    Objects.requireNonNull(remove, "remove must not be null");

    final AtomicReference<H> current = new AtomicReference<>();
    return value -> {
      final H next = apply.apply(value);
      final H prev = current.getAndSet(next);
      if (prev != null) {
        remove.accept(prev);
      }
    };
  }
}
