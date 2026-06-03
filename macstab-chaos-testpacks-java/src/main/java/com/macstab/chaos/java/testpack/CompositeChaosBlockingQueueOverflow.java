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
 * <p>Causes {@code BlockingQueue.offer()} calls to return {@code false} (queue full) at probability
 * {@link #probability()}, simulating a bounded queue that is saturated by a slow consumer and
 * forces producers to either block, discard, or propagate back-pressure.
 *
 * <h2>How it's created</h2>
 *
 * <p>Suppresses {@code QUEUE_OFFER} operations via the JVM chaos agent at the configured
 * probability, making timed and untimed offer calls report queue-full without actually being full.
 * In production, blocking-queue overflow occurs when event producers outpace consumers — common in
 * Disruptor, ArrayBlockingQueue, and similar bounded data-flow pipelines.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Producers that discard on overflow silently lose events. Producers that block will eventually
 * exhaust their thread pool. Applications must implement explicit back-pressure or overflow
 * handling; this scenario validates that they do.
 *
 * <h2>Industry references</h2>
 *
 * <p>Reactive Streams specification §1.9 mandates that publishers respect subscriber demand
 * signals (back-pressure). LMAX Disruptor documentation §"Blocking Wait Strategy" discusses
 * the trade-offs between blocking and spinning on a full ring buffer.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosBlockingQueueOverflow(probability = 0.7)
 * class BlockingQueueOverflowTest {
 *   @Test
 *   void producerAppliesBackPressureOnQueueFull(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosBlockingQueueOverflow.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.BlockingQueueOverflowComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosBlockingQueueOverflow {

  /**
   * Probability in {@code (0.0, 1.0]} that a {@code BlockingQueue.offer()} is suppressed.
   *
   * @return probability; default 0.7
   */
  double probability() default 0.7;

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
    CompositeChaosBlockingQueueOverflow[] value();
  }
}
