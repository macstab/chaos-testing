/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.queue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Blocks every consuming thread on each {@link java.util.concurrent.BlockingQueue#take()
 * BlockingQueue.take()} call until the test releases the gate or {@link #maxBlockMs} elapses,
 * giving the test precise control over exactly when the consumer is allowed to dequeue the next item.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code QUEUE_TAKE}
 * operation with the {@code gate} effect. Unlike {@link ChaosQueueTakeDelay}, which parks the
 * consumer thread for a fixed random duration and releases automatically, the gate blocks the
 * consumer thread indefinitely on an internal barrier until the test explicitly releases it — or
 * until the {@link #maxBlockMs} safety timeout fires.
 *
 * <p>The gate is the correct primitive when the test needs to assert on the producer side's
 * back-pressure state at the exact moment the consumer is frozen: for example, to verify that the
 * queue depth metric reaches a specific value, that the producer applies back-pressure, or that
 * a watchdog thread fires when the consumer stops draining.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.take()}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the consuming thread before the queue's lock is acquired.
 *   <li>The gate effect acquires an internal latch and blocks with
 *       {@code latch.await(maxBlockMs, MILLISECONDS)}.
 *   <li>The test calls the agent's gate-release API; the latch counts down, unblocking the
 *       consumer thread.
 *   <li>If {@link #maxBlockMs} elapses before the gate is released, the latch times out and the
 *       thread proceeds automatically.
 *   <li>After the gate is released, the original {@code take()} body executes: the queue lock is
 *       acquired and the head item is dequeued and returned.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.take()} does not return until the gate is released or {@link #maxBlockMs}
 *       ms pass — the consumer thread is fully blocked.
 *   <li>While the gate is held, the queue's size does not decrease from gated {@code take} calls;
 *       the queue fills if producers continue to enqueue items.
 *   <li>Producer threads that are blocked on a full-queue {@code put} call remain blocked while
 *       the consumer gate is held, because no space is freed.
 *   <li>Multiple consumer threads that hit the gate simultaneously are all held and released
 *       together when the gate is opened.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a downstream processing service is slow (GC pause,
 * external call hang) and stops consuming from its input queue; the queue fills, producers are
 * back-pressured or start dropping events, and upstream services begin timing out — the gate
 * simulates this stall with a deterministic release point.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#take()} on all
 * concrete JDK implementations via Byte Buddy retransformation. The gate latch fires before the
 * queue's own lock — the queue is not locked during the gate wait, so producers can continue
 * enqueuing items into available capacity while the consumer is gated.
 *
 * <p><strong>Queue fill dynamics.</strong> While the consumer gate is held, items accumulate in
 * the queue at the producer's rate. If the queue is bounded, it fills in {@code capacity /
 * producer_rate} time; once full, producer {@code put} calls start blocking too. This creates a
 * two-stage chain: consumer freeze → queue full → producer freeze — testing the full
 * back-pressure propagation path.
 *
 * <p><strong>Gate release semantics.</strong> A single release signal unblocks all consumer
 * threads currently parked at the gate. Newly arriving consumer threads after the release pass
 * through immediately. To re-gate future {@code take} calls, reset the gate via the agent API.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueueTakeDelay} releases
 * automatically after a fixed sleep — no test coordination needed. {@link ChaosQueuePollSuppress}
 * makes the non-blocking {@code poll} return {@code null}. This annotation is the only one that
 * freezes the consumer and requires an external release — enabling precise queue-depth assertions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueueTakeGate(maxBlockMs = 10_000)
 * class GatedConsumerTest {
 *
 *   @Test
 *   void queueDepthReachesCapacityWhileConsumerFrozen(
 *       AppConnectionInfo info, ChaosGateControl gate) throws Exception {
 *     client.startProducing(info, 100); // producer sends 100 items
 *     // consumer is blocked at gate; assert queue fills
 *     assertThat(metrics.queueDepth(info)).isEqualTo(metrics.queueCapacity(info));
 *     // release consumer; queue drains
 *     gate.release();
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#QUEUE_TAKE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#queue(java.util.Set)
 * @see ChaosQueueTakeDelay
 * @see ChaosQueuePollSuppress
 */
@Repeatable(ChaosQueueTakeGate.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_TAKE)
public @interface ChaosQueueTakeGate {

  /**
   * @return maximum block duration in milliseconds
   */
  long maxBlockMs() default 30_000L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosQueueTakeGate(id = "primary",  probability = 0.001)
   * @ChaosQueueTakeGate(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosQueueTakeGate[] value();
  }
}
