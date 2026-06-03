/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Injects a delay into {@code CompletableFuture.cancel()} calls with probability
 * {@link #probability()}, simulating a hung async pipeline where cancellation requests are slow to
 * propagate and pending stages continue executing after the caller has given up waiting.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code ASYNC_CANCEL} operations via the JVM chaos agent and delays them at the
 * configured probability. In production, slow cancellation propagation occurs when async pipelines
 * do not check the {@code Thread.interrupted()} flag or do not monitor their futures' cancellation
 * status, wasting CPU and I/O on work that will be discarded.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Slow cancellation causes phantom work — resources (threads, connections) consumed by futures
 * whose results are no longer needed. In high-throughput systems this leads to resource exhaustion
 * even though the application believes it has cancelled the work.
 *
 * <h2>Industry references</h2>
 *
 * <p>Java Concurrency in Practice §7.1.2 discusses cancellation and interruption. The
 * {@code CompletableFuture.cancel()} Javadoc notes that it may not stop computation already in
 * progress in the common-pool.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosCompletableFutureCancellation(probability = 0.4)
 * class AsyncCancellationTest {
 *   @Test
 *   void asyncPipelineCleansUpAfterCancellation(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosCompletableFutureCancellation.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.CompletableFutureCancellationComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosCompletableFutureCancellation {

  /**
   * Probability in {@code (0.0, 1.0]} that a {@code CompletableFuture.cancel()} is delayed.
   *
   * @return probability; default 0.4
   */
  double probability() default 0.4;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosCompletableFutureCancellation[] value();
  }
}
