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
 * Parks the calling thread for {@link #delayMs} to {@link #maxDelayMs} milliseconds before every
 * {@link java.util.concurrent.BlockingQueue#put(Object) BlockingQueue.put(item)} call, adding
 * artificial latency to the blocking enqueue path without preventing the item from eventually being
 * placed on the queue.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code QUEUE_PUT}
 * operation with the {@code delay} effect. It intercepts every blocking {@code put(item)} call on
 * {@code BlockingQueue} implementations in the container JVM and parks the calling thread for the
 * configured duration before allowing the blocking enqueue to proceed. After the sleep, {@code put}
 * executes normally — it blocks until space is available and then enqueues the item, or it returns
 * immediately if the queue has capacity.
 *
 * <p>The net effect is that the producer thread takes at least {@link #delayMs} ms longer per item
 * regardless of queue occupancy, reducing producer throughput in proportion to the delay.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.put(Object)}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before the queue's internal lock is
 *       acquired.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code [delayMs,
 *       maxDelayMs]}), parking the thread.
 *   <li>After the sleep, the original {@code put} body executes: the lock is acquired and the
 *       thread waits until space is available, then the item is enqueued and a consumer is
 *       notified.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.put(item)} eventually completes (the item is enqueued) but takes at least
 *       {@link #delayMs} ms longer than without chaos.
 *   <li>Producer throughput drops — the producer can place at most {@code 1000/delayMs} items per
 *       second regardless of queue capacity or consumer speed.
 *   <li>With a bounded queue and a fast consumer, the delay stretches the window during which the
 *       queue is empty (consumer drains faster than producer fills).
 *   <li>Thread-level timing: the producer thread is blocked (sleeping) longer, which reduces its
 *       CPU usage during the delay but ties up the thread handle.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a write-ahead-log producer calls {@code put} to hand
 * off log entries to a background flusher thread; a slow disk or lock contention on the flusher
 * side causes the queue to fill, forcing the producer's {@code put} to block — combined with the
 * injected delay, the producer stalls for the delay even when the queue has space, causing upstream
 * request latency to spike.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#put(Object)} on
 * all JDK concrete implementations via Byte Buddy retransformation. The delay fires before the
 * queue's {@code ReentrantLock} (for {@code LinkedBlockingQueue} / {@code ArrayBlockingQueue}) is
 * acquired — no lock contention is introduced by the sleep itself.
 *
 * <p><strong>Interaction with the blocking wait.</strong> After the sleep, the thread enters the
 * queue's internal blocking wait (via {@code Condition.await}) if the queue is full. The total
 * block time for the caller is therefore {@code delayMs + queue_wait_time}. If the consumer drains
 * quickly, the queue_wait_time is zero and only the delay is visible.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueuePutGate} blocks until the test
 * explicitly releases the producer — no auto-release. {@link ChaosQueueOfferDelay} targets the
 * non-blocking {@code offer} path. {@link ChaosQueueOfferSuppress} discards items silently. This
 * annotation preserves the blocking semantics of {@code put} but stretches its latency.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueuePutDelay(delayMs = 100)
 * class SlowPutTest {
 *
 *   @Test
 *   void producerThroughputDropsWhenPutIsSlowed(AppConnectionInfo info) throws Exception {
 *     long start = System.currentTimeMillis();
 *     client.putBatch(info, 5); // 5 puts * 100ms delay = 500ms minimum
 *     long elapsed = System.currentTimeMillis() - start;
 *     assertThat(elapsed).isGreaterThanOrEqualTo(500L);
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
 * @see ChaosQueuePutGate
 * @see ChaosQueueOfferDelay
 */
@Repeatable(ChaosQueuePutDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_PUT)
public @interface ChaosQueuePutDelay {

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
   * @ChaosQueuePutDelay(id = "primary",  probability = 0.001)
   * @ChaosQueuePutDelay(id = "replica",  probability = 0.01)
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
    ChaosQueuePutDelay[] value();
  }
}
