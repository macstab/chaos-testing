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
 * Creates a large backlog of objects with slow {@code finalize()} methods faster than the JVM's
 * single finalizer thread can drain them, simulating GC starvation caused by finalizable object
 * accumulation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor allocates {@link #objectCount()} synthetic objects whose {@code finalize()} method
 * sleeps for {@link #finalizerDelayMs()} milliseconds, then makes all of them unreachable so the
 * GC enqueues them on the finalizer queue faster than the finalizer thread can process them.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent creates {@link #objectCount()} instances of a synthetic class that overrides
 *       {@code Object.finalize()} with a body that calls {@code Thread.sleep(finalizerDelayMs)}.
 *   <li>The stressor drops all strong references to these objects, making them finalizable. The
 *       next GC cycle discovers them, promotes them to the finalizer queue (the
 *       {@code java.lang.ref.Finalizer} reference queue), and hands them to the single
 *       {@code Finalizer} thread.
 *   <li>The {@code Finalizer} thread processes finalizable objects one at a time, sequentially.
 *       With {@code objectCount = 1000} and {@code finalizerDelayMs = 100}, the thread requires
 *       100 seconds to drain the queue; during this time, the objects' memory cannot be reclaimed
 *       by the GC.
 *   <li>The backlog accumulates: GC sees the objects as alive (pending finalisation) and cannot
 *       free their heap space. If new allocations are also happening, heap pressure escalates until
 *       an OOM occurs or GC frequency spikes.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Heap exhaustion despite garbage availability.</strong> The finalizable objects are
 *       technically garbage but cannot be freed until their finalizers run; heap usage grows even
 *       though there is no actual application memory leak. Assert that the application detects the
 *       heap pressure via an OOM handler or a GC overhead alert rather than silently slowing down.
 *   <li><strong>GC overhead alarm triggers.</strong> The JVM's {@code GcOverheadLimit} (default
 *       98% of time in GC with less than 2% heap reclaimed) may fire when the finalizer backlog
 *       prevents reclamation; assert that the alarm is surfaced to the monitoring system.
 *   <li><strong>Resource starvation for finalizer-dependent resources.</strong> Some JDK classes
 *       (older {@code FileInputStream}, some JDBC drivers) release native resources in
 *       {@code finalize()}; a backed-up finalizer queue means file descriptors or database
 *       connections are not released promptly; assert that connection-pool exhaustion is handled
 *       gracefully.
 *   <li><strong>Production failure mode:</strong> frameworks that use {@code finalize()} for
 *       safety-net resource cleanup (e.g. early Netty, some JPA providers) create a finalizer
 *       backlog whenever the application creates objects faster than it closes them properly; the
 *       backlog then accumulates until a liveness probe failure or OOM kills the container.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The JVM finalisation mechanism works as follows: when a GC cycle discovers that an object is
 * unreachable and the object has a non-trivial {@code finalize()} method (one overriding
 * {@code Object.finalize()} with a non-empty body), the GC does not immediately free the object.
 * Instead, it wraps it in a {@code java.lang.ref.Finalizer} reference and enqueues it on a
 * {@code ReferenceQueue}. A single JVM-internal daemon thread — the {@code Finalizer} thread —
 * dequeues these references and calls {@code finalize()} on each object in turn. Only after
 * {@code finalize()} returns is the object eligible for a second GC pass that actually frees its
 * memory.
 *
 * <p>The critical implication is that the {@code Finalizer} thread is a bottleneck: it is a single
 * thread with no configurable parallelism. Any slowdown in individual finalizers — I/O, sleeping,
 * or lock contention inside {@code finalize()} — backs up the entire queue. The stressor
 * deliberately introduces a {@code Thread.sleep(finalizerDelayMs)} inside each finalizer to
 * reproduce this scenario.
 *
 * <p>Objects waiting for finalisation are called "finalizer-reachable" and count as live from the
 * GC's perspective (they are referenced by the {@code Finalizer} object on the queue). The GC
 * cannot reclaim their heap space until after {@code finalize()} completes and a subsequent GC
 * cycle runs. This two-pass reclamation is why finalizable objects add memory pressure even after
 * they have become unreachable from application code.
 *
 * <p>Modern Java best practice is to avoid {@code finalize()} entirely in favour of
 * {@code java.lang.ref.Cleaner} (Java 9+) or explicit {@code AutoCloseable} patterns, which do not
 * suffer from this single-thread bottleneck. This stressor is therefore most relevant for testing
 * applications that depend on legacy libraries still using {@code finalize()} for resource cleanup.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosFinalizerBacklog(objectCount = 500, finalizerDelayMs = 200)
 * class FinalizerBacklogTest {
 *   @Test
 *   void connectionPoolDoesNotExhaustUnderFinalizerBacklog(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.
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
@Repeatable(ChaosFinalizerBacklog.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.FinalizerBacklogTranslator")
public @interface ChaosFinalizerBacklog {

  /**
   * @return number of objects with slow finalizers to create
   */
  int objectCount() default 1000;

  /**
   * @return per-finalizer sleep in milliseconds
   */
  long finalizerDelayMs() default 100L;

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
   * @ChaosFinalizerBacklog(id = "primary",  probability = 0.001)
   * @ChaosFinalizerBacklog(id = "replica",  probability = 0.01)
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
    ChaosFinalizerBacklog[] value();
  }
}
