/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.thread;

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
 * Parks the calling thread inside the virtual-thread start path for the configured number of
 * milliseconds before the virtual thread is submitted to its carrier-thread pool — every
 * {@code Thread.ofVirtual().start()} call takes at least {@code delayMs} longer than normal.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code THREAD} selector family with the {@code delay}
 * effect applied to the {@code VIRTUAL_THREAD_START} operation (Project Loom, JDK 21+). It
 * intercepts the moment a virtual thread is about to be mounted onto a carrier thread in the
 * fork-join pool and artificially inflates the latency of that mounting step. The annotation is
 * declared on the test class or method alongside a container annotation and is active for the
 * lifetime of the annotated scope (class-scope: {@code beforeAll} to {@code afterAll};
 * method-scope: {@code beforeEach} to {@code afterEach}).
 *
 * <p>This annotation is specific to virtual threads and does <em>not</em> fire for platform
 * (OS-backed) threads. Use {@code @ChaosThreadStartDelay} to target platform threads, or both
 * annotations together to affect all thread creation.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the internal virtual-thread start path.
 * When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the virtual thread's {@code Runnable} is submitted to the
 *       carrier fork-join pool.
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the <em>calling</em> thread for
 *       the configured duration in milliseconds.
 *   <li>After the park returns, the virtual thread is submitted to the pool normally and begins
 *       mounting on the next available carrier.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Wall-clock time between calling {@code Thread.ofVirtual().start(task)} and the virtual
 *       thread's first instruction is at least {@code delayMs} — assert with a timestamp taken
 *       inside the task runnable.
 *   <li>Frameworks that spawn one virtual thread per incoming request (e.g. Project Loom-based
 *       HTTP servers) exhibit elevated request-latency p99 by at least {@code delayMs} — assert
 *       via client-side timing.
 *   <li>The virtual thread eventually runs its {@code Runnable} normally after the delay; assert
 *       that results are produced to distinguish from a reject.
 *   <li>Structured-concurrency scopes ({@code StructuredTaskScope}) that fork many virtual threads
 *       observe a cumulative delay proportional to the number of forks if the calling thread is
 *       shared across forks.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a Loom-based HTTP server under high
 * concurrency where the carrier fork-join pool's task queue is saturated and submission latency
 * spikes by hundreds of milliseconds — causing the 99th-percentile response time to breach SLOs
 * even though no individual request fails, because each request's virtual thread start is delayed
 * before any application code runs.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> Virtual thread creation in JDK 21+ ultimately calls
 * {@code VirtualThread#start()} (package-private in {@code java.lang}) which submits a
 * {@code Continuation} to the carrier fork-join pool. The agent targets this internal
 * {@code start()} override on the {@code VirtualThread} subclass, distinct from the public
 * {@code Thread.start()} used by platform threads. The bootstrap class loader channel is required
 * because {@code VirtualThread} is a JDK-internal class.
 *
 * <p><strong>Who parks.</strong> The park applies to the calling (launching) thread. In a
 * request-per-virtual-thread server this is typically an I/O thread dispatching accepted
 * connections; parking it blocks acceptance of subsequent connections for the delay window,
 * creating backpressure at the accept loop rather than inside the application.
 *
 * <p><strong>Carrier-pool interaction.</strong> The delay fires before the {@code Runnable} enters
 * the carrier pool's work queue. The fork-join pool is unaffected during the delay — its workers
 * continue draining queued tasks from other virtual threads. Only the submission of the new task is
 * held back, so already-running virtual threads are not slowed.
 *
 * <p><strong>Distinction from {@code ChaosThreadStartDelay}.</strong> {@code ChaosThreadStartDelay}
 * targets {@code Thread.start()} and fires for both platform and virtual threads. This annotation
 * targets the virtual-thread-specific start path exclusively. Use this annotation when the
 * application uses a mixed thread model and you want to fault only the Loom path without affecting
 * platform-thread pools.
 *
 * <p><strong>Structured concurrency.</strong> {@code StructuredTaskScope.fork()} creates a virtual
 * thread per forked task. Delaying each fork independently causes the scope's {@code join()} to
 * wait longer; if the scope has a deadline ({@code .joinUntil(instant)}), forks whose start delay
 * exceeds the remaining deadline are never mounted — assert that the scope's cancellation handler
 * is invoked.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosVirtualThreadStartDelay(delayMs = 300)
 * class VirtualThreadLatencyTest {
 *
 *   @Test
 *   void requestLatencyIncreasesByStartDelay(AppConnectionInfo info) {
 *     Instant before = Instant.now();
 *     client.get(info, "/api/data");
 *     assertThat(Duration.between(before, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(300));
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java 21+ container image — virtual threads require JDK 21 or later.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#VIRTUAL_THREAD_START
 * @see com.macstab.chaos.jvm.api.ChaosSelector#thread(java.util.Set)
 * @see ChaosVirtualThreadStartReject
 * @see ChaosThreadStartDelay
 */
@Repeatable(ChaosVirtualThreadStartDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD,
    operationType = OperationType.VIRTUAL_THREAD_START)
public @interface ChaosVirtualThreadStartDelay {

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
   * @ChaosVirtualThreadStartDelay(id = "primary",  probability = 0.001)
   * @ChaosVirtualThreadStartDelay(id = "replica",  probability = 0.01)
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
    ChaosVirtualThreadStartDelay[] value();
  }
}
