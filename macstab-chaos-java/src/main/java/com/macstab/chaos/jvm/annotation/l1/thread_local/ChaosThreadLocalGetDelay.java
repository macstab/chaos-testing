/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.thread_local;

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
 * Intercepts {@code ThreadLocal.get()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the thread-local value is returned, simulating contention or latency in
 * the thread-local access path used by Spring's {@code TransactionSynchronizationManager},
 * Hibernate's session binding, SLF4J's MDC, and any framework that propagates per-request
 * context via thread-local storage.
 *
 * <h2>What this annotation is</h2>
 *
 * A JVM agent L1 chaos primitive — one typed annotation per (selector family, operation type,
 * effect) tuple. It is declared on a test class or method alongside a container annotation and
 * activates for the lifetime of the test class ({@code beforeAll} / {@code afterAll}) or a single
 * test method ({@code beforeEach} / {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>Before every call to {@code java.lang.ThreadLocal#get()} inside the target container's
 *       JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code get()} executes normally, performing the
 *       {@code Thread.threadLocals} map lookup and returning the stored value (or invoking
 *       {@code initialValue()} if not yet set).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ThreadLocal.get()} is called many thousands of times per request in typical Spring
 *       applications; the delay fires on every call and compounds rapidly; even a 1 ms delay
 *       can add hundreds of milliseconds to a single request that accesses thread-local state
 *       hundreds of times; assert that the application's latency is sensitive to thread-local
 *       access overhead and that critical paths minimise repeated gets.
 *   <li>Spring's {@code TransactionSynchronizationManager} stores the current transaction's
 *       connection binding, synchronisation list, and transaction name all in separate
 *       {@code ThreadLocal} fields; each {@code @Transactional} method accesses these fields
 *       multiple times; the delay inflates every transactional operation proportionally.
 *   <li>SLF4J's MDC ({@code MDC.get(key)}) uses a {@code ThreadLocal<Map>}; every log statement
 *       that includes MDC keys calls {@code get()}; a write-heavy application logging many events
 *       per request will see total latency increase significantly.
 *   <li><strong>Production failure mode:</strong> a profiler or monitoring agent installed on the
 *       JVM intercepts {@code ThreadLocal.get()} to capture context propagation; a buggy version
 *       of the agent adds a millisecond of latency to every call; applications with tight SLOs
 *       exceed their p99 latency budget; the root cause is invisible in application traces because
 *       the delay fires below the tracing instrumentation layer.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ThreadLocal#get()}. Internally, {@code get()} reads
 * the current thread's {@code Thread.threadLocals} field (of type {@code ThreadLocal.ThreadLocalMap}),
 * then performs a hash-based probe to find the entry for the given {@code ThreadLocal} instance.
 * The map uses linear probing with open addressing; on collision, it scans forward. The chaos
 * delay fires before this lookup, adding a predictable JVM-level delay.
 *
 * <p>Spring's {@code TransactionSynchronizationManager} uses the following thread-local fields,
 * each accessed via {@code ThreadLocal.get()}: {@code resources} (connection binding map),
 * {@code synchronizations} (synchronisation list), {@code currentTransactionName},
 * {@code currentTransactionReadOnly}, {@code currentTransactionIsolationLevel}, and
 * {@code actualTransactionActive}. A single {@code @Transactional} method that performs one
 * database query accesses these fields approximately 6-10 times; with a 10 ms delay each,
 * the transactional overhead becomes 60-100 ms, drowning out the actual query time.
 *
 * <p>Hibernate's {@code SessionImpl} accesses thread-local state via Spring's
 * {@code TransactionSynchronizationManager} for session binding; the delay compounds with the
 * Spring transaction overhead above. For every flush, Hibernate iterates its persistence context
 * and calls {@code get()} multiple times for dirty-checking.
 *
 * <p>{@code InheritableThreadLocal} (a subclass of {@code ThreadLocal}) is also intercepted by
 * the same mechanism, affecting frameworks that propagate context from parent to child threads
 * (e.g. Hystrix's command isolation, CompletableFuture with custom executors).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadLocalGetDelay(delayMs = 5)
 * class ThreadLocalOverheadTest {
 *   @Test
 *   void p99LatencyExceedsThresholdDueToThreadLocalAccessOverhead(ConnectionInfo info) {
 *     // assert that transactional endpoints show disproportionate latency increase
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the
 *       translator class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosThreadLocalGetSuppress
 * @see ChaosThreadLocalSetSuppress
 */
@Repeatable(ChaosThreadLocalGetDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD_LOCAL,
    operationType = OperationType.THREAD_LOCAL_GET)
public @interface ChaosThreadLocalGetDelay {

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
   * @ChaosThreadLocalGetDelay(id = "primary",  probability = 0.001)
   * @ChaosThreadLocalGetDelay(id = "replica",  probability = 0.01)
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
    ChaosThreadLocalGetDelay[] value();
  }
}
