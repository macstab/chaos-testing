/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Floods the JVM's internal reference-processing queue with a burst of {@code WeakReference}
 * objects on a tight cycle, overwhelming the {@code ReferenceHandler} thread and stalling GC
 * reference processing.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept a
 * specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor allocates {@link #referenceCount()} {@code WeakReference} objects per cycle, makes
 * their referents unreachable, and then sleeps for {@link #floodIntervalMs()} milliseconds before
 * repeating, causing a continuous stream of cleared references to flow through the GC's reference
 * queue.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>On each cycle, the stressor creates {@link #referenceCount()} {@code WeakReference}
 *       instances, each wrapping a small distinct object, and registers each with a shared {@code
 *       ReferenceQueue}.
 *   <li>The stressor drops all strong references to the referents (but not to the {@code
 *       WeakReference} wrappers). At the next GC cycle, the GC detects that the referents are
 *       weakly reachable and clears the references, enqueuing the {@code WeakReference} objects on
 *       the registered queue.
 *   <li>The JVM's {@code ReferenceHandler} thread — a single high-priority internal daemon thread —
 *       dequeues the cleared references and dispatches them for further processing (calling
 *       registered {@code Cleaner} actions, or simply making them available to application code via
 *       {@code queue.poll()}).
 *   <li>With a large {@link #referenceCount()} and a short {@link #floodIntervalMs()}, the queue
 *       grows faster than the {@code ReferenceHandler} can drain it, stalling GC reference
 *       processing for all reference types (weak, soft, phantom) that share the same queue.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Delayed soft-reference eviction.</strong> The JVM evicts soft references before
 *       throwing OOM; if the {@code ReferenceHandler} is backed up, soft-reference eviction
 *       notifications are delayed and the cache they protect may not shrink in time to prevent OOM;
 *       assert that the application-level cache also has a size limit independent of soft-reference
 *       eviction.
 *   <li><strong>Cleaner action delays.</strong> {@code java.lang.ref.Cleaner} (Java 9+) actions are
 *       dispatched by the reference handler or a dedicated cleaner thread; a backed-up handler
 *       delays resource cleanup (file handles, off-heap memory) registered with a {@code Cleaner};
 *       assert that the application does not leak file descriptors under queue flooding.
 *   <li><strong>Direct-buffer reclamation stalls.</strong> {@code DirectByteBuffer} objects use a
 *       phantom reference and a {@code Cleaner} to free native memory; flooding the reference queue
 *       delays this cleanup; assert that direct-memory usage does not exceed the limit ({@code
 *       MaxDirectMemorySize}) under sustained reference flooding.
 *   <li><strong>Application reference-queue consumers starved.</strong> Application code that calls
 *       {@code ReferenceQueue.remove()} to detect collected objects (e.g. a cache eviction
 *       listener) will see very high latency between the object being collected and the
 *       notification being delivered; assert that the cache eviction callback fires within a
 *       bounded time.
 *   <li><strong>Production failure mode:</strong> a cache implemented with {@code WeakReference}
 *       values that is invalidated simultaneously for many keys floods the reference queue with all
 *       the cleared entries at once, temporarily stalling GC reference processing for the entire
 *       JVM.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The JVM's reference processing pipeline consists of three stages. First, during a GC cycle,
 * the garbage collector identifies weakly/softly/phantom-reachable objects and clears them (or sets
 * a flag to clear them). Second, the {@code ReferenceHandler} thread — a single daemon thread at
 * {@code Thread.MAX_PRIORITY} — reads the pending reference list that the GC populates and enqueues
 * the references on their registered {@code ReferenceQueue}s. Third, application code (or a {@code
 * Cleaner} thread) polls the queue and acts on the notification.
 *
 * <p>The {@code ReferenceHandler} is a single thread shared across all reference types and all
 * queues in the JVM. Its throughput is bounded by how fast it can dequeue from the GC's pending
 * list and enqueue to the application queue. With a high flood rate ({@code referenceCount = 10000}
 * every 100 ms = 100,000 references per second), the handler is kept at near-100% CPU, delaying all
 * reference notifications for the entire JVM.
 *
 * <p>This stressor is complementary to {@link ChaosFinalizerBacklog}: the finalizer backlog stalls
 * the {@code Finalizer} thread (which runs {@code finalize()} methods), while this stressor stalls
 * the {@code ReferenceHandler} thread (which enqueues cleared references). Combined, they stress
 * both reference-processing subsystems simultaneously.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosReferenceQueueFlood(referenceCount = 20_000, floodIntervalMs = 50)
 * class ReferenceQueueFloodTest {
 *   @Test
 *   void directMemoryIsReleasedWithinBoundedTimeUnderReferenceFlood(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an {@code
 *       ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>Chaos agent JAR</strong> accessible at the path configured in
 *       {@code @JvmAgentChaos}.
 *   <li><strong>{@code macstab-chaos-java} on the test classpath</strong> — required for the
 *       translator.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosReferenceQueueFlood.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ReferenceQueueFloodTranslator")
public @interface ChaosReferenceQueueFlood {

  /**
   * @return references created per flood cycle
   */
  int referenceCount() default 10_000;

  /**
   * @return interval between flood cycles in ms
   */
  long floodIntervalMs() default 100L;

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
   * @ChaosReferenceQueueFlood(id = "primary",  probability = 0.001)
   * @ChaosReferenceQueueFlood(id = "replica",  probability = 0.01)
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
    ChaosReferenceQueueFlood[] value();
  }
}
