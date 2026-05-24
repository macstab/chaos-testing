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
 * Parks the consuming thread for {@link #delayMs} to {@link #maxDelayMs} milliseconds before every
 * {@link java.util.concurrent.BlockingQueue#take() BlockingQueue.take()} call, slowing down the
 * consumer's dequeue rate without preventing items from eventually being consumed.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code
 * QUEUE_TAKE} operation with the {@code delay} effect. It intercepts every blocking {@code take()}
 * call on {@code BlockingQueue} implementations in the container JVM and parks the consuming thread
 * for the configured duration before allowing the take to proceed. After the sleep, {@code take()}
 * executes normally — it blocks until an item is available and then returns the head item.
 *
 * <p>The net effect is that the consumer drains the queue {@link #delayMs} ms slower per item,
 * allowing the queue to fill faster relative to its drain rate — directly testing the queue's
 * back-pressure behaviour under a slow consumer.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.take()}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the consuming thread before the queue's lock is acquired.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code [delayMs,
 *       maxDelayMs]}), parking the thread.
 *   <li>After the sleep, the original {@code take()} body executes: the lock is acquired, the
 *       thread waits for an item if the queue is empty, and the head item is removed and returned.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.take()} returns the correct head item, but takes at least {@link #delayMs} ms
 *       longer than without chaos.
 *   <li>Consumer throughput drops — the consumer can process at most {@code 1000/delayMs} items per
 *       second regardless of queue occupancy.
 *   <li>Producer threads that call {@code put} may start blocking as the queue fills up (because
 *       the consumer is artificially slowed), allowing tests to assert on back-pressure signals.
 *   <li>Any bounded queue configured with an {@code AbortPolicy} or similar will trigger {@code
 *       RejectedExecutionException} earlier than usual once the queue fills.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a consumer microservice processes events from an
 * in-memory queue; a slow downstream (database write, HTTP call) slows each iteration's take-to-
 * process cycle — the queue fills, producers are back-pressured, and the system stalls waiting for
 * the consumer to catch up.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#take()} on all
 * concrete JDK implementations via Byte Buddy retransformation. The delay fires before the queue's
 * {@code ReentrantLock} is acquired — no lock contention is introduced by the sleep itself; other
 * threads can freely call {@code put} or {@code offer} during the sleep.
 *
 * <p><strong>Queue fill dynamics.</strong> With a producer rate of {@code P} items/s, a consumer
 * delay of {@code D} ms, and a pool of {@code C} consumers, the effective consumer rate drops to
 * {@code C * (1000/D)} items/s (ignoring item processing time). Once the consumer rate falls below
 * the producer rate, a bounded queue will fill at {@code P - consumer_rate} items per second,
 * reaching capacity in {@code capacity / (P - consumer_rate)} seconds.
 *
 * <p><strong>Interaction with the blocking wait.</strong> After the sleep, the thread enters the
 * queue's blocking wait if the queue is empty (producers are also slow). The total block time per
 * item is {@code delayMs + queue_empty_wait_time} — which is just {@code delayMs} when the queue
 * stays non-empty.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueueTakeGate} blocks until the
 * test explicitly releases the consumer — no auto-release. {@link ChaosQueuePollDelay} targets the
 * non-blocking {@code poll} path. {@link ChaosQueuePollSuppress} makes {@code poll} return {@code
 * null}. This annotation targets the blocking {@code take} path and preserves its correctness while
 * stretching its latency.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueueTakeDelay(delayMs = 100)
 * class SlowConsumerTest {
 *
 *   @Test
 *   void queueFillsWhenConsumerIsSlow(AppConnectionInfo info) throws Exception {
 *     client.startProducing(info, 20); // producer sends 20 items
 *     Thread.sleep(500); // 5 items consumed at 100ms each
 *     assertThat(metrics.queueDepth(info)).isGreaterThan(10);
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
 * @see com.macstab.chaos.jvm.api.OperationType#QUEUE_TAKE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#queue(java.util.Set)
 * @see ChaosQueueTakeGate
 * @see ChaosQueuePollDelay
 */
@Repeatable(ChaosQueueTakeDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_TAKE)
public @interface ChaosQueueTakeDelay {

  /**
   * @return min delay in milliseconds
   */
  long delayMs() default 100L;

  /**
   * @return max delay in milliseconds (defaults to delayMs for deterministic delay)
   */
  long maxDelayMs() default 100L;

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
   * @ChaosQueueTakeDelay(id = "primary",  probability = 0.001)
   * @ChaosQueueTakeDelay(id = "replica",  probability = 0.01)
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
    ChaosQueueTakeDelay[] value();
  }
}
