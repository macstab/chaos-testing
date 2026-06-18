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
 * {@link java.util.concurrent.BlockingQueue#poll() BlockingQueue.poll()} call, adding artificial
 * latency to non-blocking dequeue attempts without preventing items from eventually being consumed
 * or forcing a {@code null} return.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code
 * QUEUE_POLL} operation with the {@code delay} effect. It intercepts every non-blocking {@code
 * poll()} call on {@code BlockingQueue} implementations in the container JVM and parks the
 * consuming thread for the configured duration before allowing the poll to attempt to dequeue an
 * item. After the sleep, {@code poll()} executes normally — it returns the head item if one is
 * available, or {@code null} if the queue is empty at that moment.
 *
 * <p>This is the non-blocking-dequeue analogue of {@link ChaosQueueTakeDelay}. Where {@code take}
 * blocks until an item is available, {@code poll} returns immediately whether or not an item is
 * present — the delay only stretches the time before the check.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.poll()} (and the timed
 * variant {@code poll(long, TimeUnit)}). When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the consuming thread before the queue's lock is acquired.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code [delayMs,
 *       maxDelayMs]}), parking the thread.
 *   <li>After the sleep, the original {@code poll()} body executes: the lock is acquired, the head
 *       item is removed and returned if present, or {@code null} is returned if empty.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.poll()} returns the head item (or {@code null}) but takes at least {@link
 *       #delayMs} ms longer than normal.
 *   <li>Tight poll loops (e.g., a busy-spin consumer using {@code while (queue.poll() != null)})
 *       are slowed to at most {@code 1000/delayMs} polls per second per thread.
 *   <li>Items produced during the delay may accumulate in the queue before the poll check — the
 *       poll is more likely to return an item (not {@code null}) than it would without the delay if
 *       the producer is active.
 *   <li>Time-sensitive timed poll calls lose part of their timeout budget to the delay.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a busy-wait consumer polls at high frequency to
 * minimise latency; during a scheduler starvation event, each poll call is delayed by the OS
 * scheduler, causing the consumer loop to process items far slower than expected while consuming
 * 100% of its CPU budget in the loop itself.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#poll()} and {@code
 * BlockingQueue#poll(long, TimeUnit)} via Byte Buddy retransformation of JDK classes. The delay
 * fires before the queue's internal {@code ReentrantLock} (or CAS) is entered, so no lock
 * contention is introduced by the sleep.
 *
 * <p><strong>Race with producers.</strong> During the sleep, other threads can enqueue items. A
 * poll that would have returned {@code null} (empty queue at call time) may return an item after
 * the sleep if a producer enqueued something during the delay window. This changes the timing of
 * {@code null} vs non-{@code null} returns, potentially exposing empty-queue handling paths that
 * would never fire in normal usage patterns.
 *
 * <p><strong>Timed poll interaction.</strong> For the timed variant {@code poll(timeout, unit)},
 * the delay is additive: the total block is at most {@code delayMs + timeout}. The timeout clock
 * starts after the sleep, so the consumer waits up to the full specified timeout for an item.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueuePollSuppress} makes every
 * {@code poll} return {@code null} immediately (item dropped). {@link ChaosQueueTakeDelay} targets
 * the blocking {@code take} path. This annotation targets the non-blocking {@code poll} path and
 * preserves its correctness while stretching its timing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueuePollDelay(delayMs = 50)
 * class SlowPollTest {
 *
 *   @Test
 *   void consumerThroughputDropsWhenPollIsSlowed(AppConnectionInfo info) throws Exception {
 *     client.startProducing(info, 20);
 *     Thread.sleep(600); // 12 polls at 50ms each = 12 items consumed maximum
 *     assertThat(metrics.consumedCount(info)).isLessThanOrEqualTo(12);
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
 * @see com.macstab.chaos.jvm.api.OperationType#QUEUE_POLL
 * @see com.macstab.chaos.jvm.api.ChaosSelector#queue(java.util.Set)
 * @see ChaosQueuePollSuppress
 * @see ChaosQueueTakeDelay
 */
@Repeatable(ChaosQueuePollDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_POLL)
public @interface ChaosQueuePollDelay {

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
   * @ChaosQueuePollDelay(id = "primary",  probability = 0.001)
   * @ChaosQueuePollDelay(id = "replica",  probability = 0.01)
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
    ChaosQueuePollDelay[] value();
  }
}
