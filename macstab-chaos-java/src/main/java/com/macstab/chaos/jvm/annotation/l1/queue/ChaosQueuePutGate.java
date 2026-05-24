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
 * Blocks the calling thread on every {@link java.util.concurrent.BlockingQueue#put(Object)
 * BlockingQueue.put(item)} call until the test releases the gate or {@link #maxBlockMs} elapses,
 * giving the test precise control over exactly when a producer thread is allowed to enqueue an
 * item.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code QUEUE_PUT}
 * operation with the {@code gate} effect. Unlike {@link ChaosQueuePutDelay}, which parks the
 * producer thread for a fixed random duration and releases automatically, the gate blocks the
 * producer thread indefinitely on an internal barrier until the test explicitly releases it — or
 * until the {@link #maxBlockMs} safety timeout fires.
 *
 * <p>The gate is the correct primitive when the test needs to assert on the application's state at
 * the exact moment the producer is stuck trying to put an item: for example, to verify that the
 * consumer is alive, that back-pressure metrics are emitted, or that the application's health check
 * transitions appropriately.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.put(Object)}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the producer thread before the queue's internal lock is
 *       acquired.
 *   <li>The gate effect acquires an internal latch and blocks with {@code latch.await(maxBlockMs,
 *       MILLISECONDS)}.
 *   <li>The test calls the agent's gate-release API; the latch counts down, unblocking the producer
 *       thread.
 *   <li>If {@link #maxBlockMs} elapses before the gate is released, the latch times out and the
 *       thread proceeds automatically.
 *   <li>After the gate is released, the original {@code put} body executes: the queue lock is
 *       acquired and the item is enqueued.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.put(item)} does not return until the gate is released or {@link #maxBlockMs}
 *       ms pass — the producer thread is fully blocked.
 *   <li>While the gate is held, the queue's size does not grow from the gated {@code put} calls.
 *   <li>Consumer threads that drain the queue continue to run while the gate is held — only the
 *       producer side is frozen.
 *   <li>Multiple producer threads hitting the gate simultaneously are all held and released
 *       together when the gate is opened.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a producer service attempts to hand off events to a
 * downstream service through a shared queue; the downstream service is slow or stopped, the queue
 * fills, and all producer threads are parked indefinitely on {@code put} — the gate replicates this
 * exact stall condition with deterministic release timing for testing.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#put(Object)} on
 * all concrete JDK implementations via Byte Buddy retransformation. The gate latch is managed by
 * the agent's in-process gate registry, keyed by the plan rule identifier. The latch fires before
 * the queue's own lock, so queue-level back-pressure ({@code await(notFull)}) is additive to the
 * gate block — the producer first waits for the gate, then waits for queue space.
 *
 * <p><strong>Gate release semantics.</strong> A single release signal unblocks all threads
 * currently parked at the gate. If new producer threads arrive at the gate after the release has
 * fired, they pass through immediately (the latch is at zero). To re-gate future {@code put} calls,
 * the test must configure a new rule or reset the gate via the agent API.
 *
 * <p><strong>Interaction with interruption.</strong> While a thread is blocked on the latch, it
 * remains interruptible. If the application's shutdown logic interrupts producer threads, {@code
 * InterruptedException} is thrown from the latch, propagating up through the interceptor as-is. The
 * thread's interrupt flag is restored before the exception is thrown.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueuePutDelay} releases
 * automatically after a fixed sleep. {@link ChaosQueueOfferSuppress} discards items on the
 * non-blocking {@code offer} path. This annotation is the only one that freezes the producer thread
 * and requires an external release — enabling precise test coordination.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueuePutGate(maxBlockMs = 10_000)
 * class GatedProducerTest {
 *
 *   @Test
 *   void backPressureMetricEmittedWhileProducerBlocked(
 *       AppConnectionInfo info, ChaosGateControl gate) throws Exception {
 *     client.startProducing(info); // triggers put() internally; blocks at gate
 *     assertThat(metrics.producerBlockedCount(info)).isEqualTo(1);
 *     gate.release(); // let the put proceed
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code
 *       beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#QUEUE_PUT
 * @see com.macstab.chaos.jvm.api.ChaosSelector#queue(java.util.Set)
 * @see ChaosQueuePutDelay
 * @see ChaosQueueOfferSuppress
 */
@Repeatable(ChaosQueuePutGate.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_PUT)
public @interface ChaosQueuePutGate {

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
   * @ChaosQueuePutGate(id = "primary",  probability = 0.001)
   * @ChaosQueuePutGate(id = "replica",  probability = 0.01)
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
    ChaosQueuePutGate[] value();
  }
}
