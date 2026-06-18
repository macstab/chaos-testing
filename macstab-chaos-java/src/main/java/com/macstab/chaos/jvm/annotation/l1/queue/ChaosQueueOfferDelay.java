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
 * {@link java.util.concurrent.BlockingQueue#offer(Object) BlockingQueue.offer(item)} call,
 * stretching the time of non-blocking enqueue attempts without suppressing them or causing them to
 * return {@code false}.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code
 * QUEUE_OFFER} operation with the {@code delay} effect. It intercepts every {@code offer(item)}
 * call on {@code BlockingQueue} implementations ({@code LinkedBlockingQueue}, {@code
 * ArrayBlockingQueue}, {@code LinkedTransferQueue}, etc.) and parks the calling thread for the
 * configured duration before allowing the offer to attempt to enqueue the item. The offer itself
 * proceeds normally after the sleep — it returns {@code true} if space was available or {@code
 * false} if the queue was full at that moment.
 *
 * <p>This differs from {@link ChaosQueueOfferSuppress}, which always returns {@code false} and
 * discards the item. Here the item may still be enqueued — the delay only stretches the time before
 * the offer is attempted.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.offer(Object)} (and
 * the timed variant {@code offer(Object, long, TimeUnit)}). When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before the queue's internal lock is
 *       acquired.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code [delayMs,
 *       maxDelayMs]}), parking the calling thread.
 *   <li>After the sleep, the original {@code offer} body executes: the lock is acquired, space is
 *       checked, and the item is enqueued (or not) according to the queue's current state.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.offer(item)} returns and takes at least {@link #delayMs} ms longer than
 *       normal; the return value ({@code true} or {@code false}) reflects the queue's state at the
 *       moment after the sleep, not at the moment of the original call.
 *   <li>Producer threads that offer in a tight loop (e.g., a Disruptor-style producer) will slow
 *       down by {@link #delayMs} per item, reducing throughput proportionally.
 *   <li>Time-sensitive offer-with-timeout calls may see their deadline expire during the delay,
 *       causing them to return {@code false} even if queue space would have been available.
 *   <li>Consumer threads that were racing for space in the queue have more time to drain during the
 *       delay, potentially making the offer succeed more often than it would without chaos.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a producer microservice offers events to a bounded
 * in-memory queue; momentary GC pauses cause every offer to take longer than the consumer drain
 * rate can compensate for, filling the queue and causing subsequent offers to return {@code false}
 * and drop events silently.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#offer(Object)} and
 * {@code BlockingQueue#offer(Object, long, TimeUnit)} via Byte Buddy retransformation of the JDK's
 * {@code java.util.concurrent} classes. Concrete implementations are intercepted via their
 * overriding methods.
 *
 * <p><strong>Lock acquisition timing.</strong> The delay fires before the queue's internal {@code
 * ReentrantLock} (for {@code LinkedBlockingQueue} / {@code ArrayBlockingQueue}) or CAS loop (for
 * {@code ConcurrentLinkedQueue}) is entered. During the sleep, no queue lock is held by the
 * sleeping thread — other threads can freely enqueue or dequeue items, changing the queue's state
 * during the delay window.
 *
 * <p><strong>Timed offer interaction.</strong> For the timed variant, the remaining timeout passed
 * to the underlying park is not reduced by the delay — the delay is additive. An offer called with
 * {@code offer(item, 100, MILLIS)} may block for up to {@code delayMs + 100} ms in total.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueueOfferSuppress} always returns
 * {@code false} and never enqueues the item. {@link ChaosQueuePutDelay} targets the blocking {@code
 * put} path (which never returns false). This annotation targets the non-blocking {@code offer}
 * path and preserves its correctness while stretching its timing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueueOfferDelay(delayMs = 50, maxDelayMs = 200)
 * class SlowOfferTest {
 *
 *   @Test
 *   void producerThroughputDropsWhenOffersAreSlowed(AppConnectionInfo info) throws Exception {
 *     long start = System.currentTimeMillis();
 *     int offered = client.offerBatch(info, 10);
 *     long elapsed = System.currentTimeMillis() - start;
 *     // at least 10 * 50ms = 500ms of injected delay
 *     assertThat(elapsed).isGreaterThanOrEqualTo(500L);
 *     assertThat(offered).isLessThanOrEqualTo(10);
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
 * @see com.macstab.chaos.jvm.api.OperationType#QUEUE_OFFER
 * @see com.macstab.chaos.jvm.api.ChaosSelector#queue(java.util.Set)
 * @see ChaosQueueOfferSuppress
 * @see ChaosQueuePutDelay
 */
@Repeatable(ChaosQueueOfferDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_OFFER)
public @interface ChaosQueueOfferDelay {

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
   * @ChaosQueueOfferDelay(id = "primary",  probability = 0.001)
   * @ChaosQueueOfferDelay(id = "replica",  probability = 0.01)
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
    ChaosQueueOfferDelay[] value();
  }
}
