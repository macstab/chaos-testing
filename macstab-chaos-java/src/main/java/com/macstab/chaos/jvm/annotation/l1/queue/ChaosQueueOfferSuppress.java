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
 * Makes every {@link java.util.concurrent.BlockingQueue#offer(Object) BlockingQueue.offer(item)}
 * call return {@code false} without enqueuing the item — the producer believes the queue is full
 * and the item is silently discarded.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code
 * QUEUE_OFFER} operation with the {@code suppress} effect. It intercepts every non-blocking offer
 * to any {@code BlockingQueue} in the container JVM and short-circuits the operation, returning
 * {@code false} to the caller as if the queue were full. The item is never enqueued; no lock is
 * acquired; no consumer thread is notified. The queue's actual occupancy is unchanged.
 *
 * <p>This directly replicates the normal queue-full rejection from {@code offer} — the same return
 * value a producer sees during genuine back-pressure — without requiring the queue to actually be
 * full.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.offer(Object)}. When
 * the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before any queue-internal state is
 *       touched.
 *   <li>The suppress effect returns {@code false} immediately without acquiring any lock.
 *   <li>The item reference is not stored anywhere — it is eligible for GC.
 *   <li>No consumer thread is notified of a new element, because no element was added.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.offer(item)} returns {@code false} — every time, as long as the annotation is
 *       active.
 *   <li>{@code queue.size()} is unchanged after the suppressed offer.
 *   <li>Producer code that checks the return value and applies a drop-on-full policy will silently
 *       drop all items, even if the queue has available capacity.
 *   <li>Producer code that logs a warning on {@code false} and retries may loop indefinitely (or
 *       until the annotation is removed), generating constant log output.
 *   <li>Consumer threads blocked on {@code queue.take()} continue to block — no item arrives.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> an event-driven service writes to an internal
 * bounded queue and drops events when {@code offer} returns {@code false} (correct behaviour under
 * normal back-pressure); during an upstream burst the queue fills and subsequent events are
 * silently dropped — this annotation tests that the downstream consumer detects the drop and emits
 * the correct metrics or triggers a dead-letter queue.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#offer(Object)} on
 * all concrete JDK implementations ({@code LinkedBlockingQueue}, {@code ArrayBlockingQueue}, {@code
 * PriorityBlockingQueue}, {@code SynchronousQueue}, etc.) via Byte Buddy retransformation. Because
 * the suppress fires before the lock is acquired, it is invisible to the queue's internal state
 * machine — the queue does not know an offer was attempted.
 *
 * <p><strong>SynchronousQueue specifics.</strong> {@code SynchronousQueue.offer} returns {@code
 * false} immediately if no consumer thread is already waiting. The suppress makes every offer
 * return {@code false} regardless of whether a consumer is waiting — including cases where the
 * transfer would normally succeed.
 *
 * <p><strong>Cascading effects.</strong> Producers that use {@code offer} with a fallback (e.g.,
 * write to a database when the in-memory queue is full) will activate the fallback on every
 * message, potentially overwhelming the fallback store. Test that the fallback path is both
 * functional and appropriately rate-limited.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueueOfferDelay} delays but allows
 * the offer to succeed or fail naturally. {@link ChaosQueuePutGate} blocks the producer on the
 * blocking {@code put} path. This annotation is the only one that always reports queue-full without
 * touching the queue, suitable for testing drop-on-full logic.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueueOfferSuppress
 * class OfferSuppressedTest {
 *
 *   @Test
 *   void producerDropsEventsAndEmitsMetricWhenQueueRejectsAll(AppConnectionInfo info) {
 *     client.sendBatch(info, 100);
 *     // all offers were suppressed; metric should record 100 drops
 *     assertThat(metrics.droppedEventCount(info)).isEqualTo(100);
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
 * @see ChaosQueueOfferDelay
 * @see ChaosQueuePutGate
 */
@Repeatable(ChaosQueueOfferSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_OFFER)
public @interface ChaosQueueOfferSuppress {

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
   * @ChaosQueueOfferSuppress(id = "primary",  probability = 0.001)
   * @ChaosQueueOfferSuppress(id = "replica",  probability = 0.01)
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
    ChaosQueueOfferSuppress[] value();
  }
}
