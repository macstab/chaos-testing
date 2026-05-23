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
 * Makes every {@link java.util.concurrent.BlockingQueue#poll() BlockingQueue.poll()} call return
 * {@code null} without removing any item from the queue — the consumer believes the queue is empty
 * and the items remain stranded in the queue indefinitely.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code QUEUE} selector family targeting the {@code QUEUE_POLL}
 * operation with the {@code suppress} effect. It intercepts every non-blocking {@code poll()} call
 * on {@code BlockingQueue} implementations in the container JVM and short-circuits the operation,
 * returning {@code null} to the caller as if the queue were empty. No item is removed from the
 * queue; no lock is acquired; no queue state changes. The queue's actual occupancy is unchanged.
 *
 * <p>This directly replicates the normal empty-queue return from {@code poll()} — the same
 * {@code null} a consumer sees when no items are available — without requiring the queue to
 * actually be empty.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code BlockingQueue.poll()}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the consuming thread before any queue-internal state is
 *       touched.
 *   <li>The suppress effect returns {@code null} immediately without acquiring any lock.
 *   <li>The item at the head of the queue remains in the queue — it is not consumed or discarded.
 *   <li>No producer thread waiting on a full-queue condition is notified, because no space was
 *       freed.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code queue.poll()} returns {@code null} — every time, as long as the annotation is
 *       active, even if items are present.
 *   <li>{@code queue.size()} is unchanged after the suppressed poll — items accumulate.
 *   <li>Consumer code that loops on {@code poll()} and exits on {@code null} will exit immediately
 *       on every iteration, effectively becoming a no-op consumer.
 *   <li>Consumer code that treats {@code null} as "no work available" and spins will busy-spin at
 *       maximum CPU rate without processing any items.
 *   <li>Producers blocked on a full-queue {@code put} continue to block — the consumer's
 *       suppressed poll frees no space.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a polling consumer reads from an in-memory queue
 * with {@code poll()} and processes events; a bug in the consumer's null check causes it to drop
 * the item on a spurious empty return — this annotation tests that empty-return handling is correct
 * and does not lose items silently.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code BlockingQueue#poll()} on all
 * concrete JDK implementations via Byte Buddy retransformation. Because the suppress fires before
 * the queue's lock is acquired, the queue's internal state is completely unchanged — the item
 * count, the head pointer, and any producer condition signals are all untouched.
 *
 * <p><strong>Queue growth dynamics.</strong> With all {@code poll} calls suppressed and producers
 * still running, a bounded queue will fill at the producer's rate. Once full, producers calling
 * {@code offer} will receive {@code false} and producers calling {@code put} will block. This
 * turns the suppress into a secondary stress on the offer/put paths — tests should verify
 * producer back-pressure handling as well.
 *
 * <p><strong>Interaction with timed poll.</strong> The timed variant {@code poll(timeout, unit)}
 * is also suppressed — it returns {@code null} without waiting for the timeout and without
 * removing any item.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosQueuePollDelay} allows the poll
 * to succeed or return {@code null} naturally, just later. {@link ChaosQueueTakeGate} blocks the
 * consumer on the blocking {@code take} path until explicitly released. This annotation is the
 * only one that makes the consumer believe the queue is empty when it is not — suitable for
 * testing empty-return handling without actually draining the queue.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosQueuePollSuppress
 * class PollSuppressedTest {
 *
 *   @Test
 *   void itemsRemainInQueueWhenPollsAreDropped(AppConnectionInfo info) throws Exception {
 *     client.sendBatch(info, 10);
 *     Thread.sleep(500); // consumer polls are all suppressed
 *     assertThat(metrics.queueDepth(info)).isEqualTo(10);
 *     assertThat(metrics.consumedCount(info)).isEqualTo(0);
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
 * @see com.macstab.chaos.jvm.api.OperationType#QUEUE_POLL
 * @see com.macstab.chaos.jvm.api.ChaosSelector#queue(java.util.Set)
 * @see ChaosQueuePollDelay
 * @see ChaosQueueTakeGate
 */
@Repeatable(ChaosQueuePollSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.QUEUE,
    operationType = OperationType.QUEUE_POLL)
public @interface ChaosQueuePollSuppress {

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
   * @ChaosQueuePollSuppress(id = "primary",  probability = 0.001)
   * @ChaosQueuePollSuppress(id = "replica",  probability = 0.01)
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
    ChaosQueuePollSuppress[] value();
  }
}
