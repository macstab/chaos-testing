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
 * Simulates a ThreadLocal memory leak by planting large, never-removed {@code ThreadLocal} values
 * into pool threads, reproducing the pattern where a framework stores per-request data in a thread
 * from a reusable pool and forgets to clean up after the request completes.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor submits tasks to the common fork-join pool (or a known thread pool in the
 * container's JVM) that call {@code ThreadLocal.set()} with a large byte-array value and then
 * return without calling {@code ThreadLocal.remove()}, leaving the value permanently attached to
 * the pool thread.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent identifies pool threads in the target JVM (the common fork-join pool's workers
 *       or the application's servlet thread pool, depending on configuration).</li>
 *   <li>A task is submitted to each pool thread that calls {@code threadLocal.set(new
 *       byte[valueSizeBytes])} {@link #entriesPerThread()} times using distinct
 *       {@code ThreadLocal} instances. The task returns without removing any of the values.</li>
 *   <li>Because pool threads are never terminated (they are reused for future tasks), the
 *       {@code ThreadLocal} values are retained indefinitely. Each value is reachable via:
 *       {@code Thread → ThreadLocalMap → Entry → value}. The GC cannot collect these values as
 *       long as the thread is alive.</li>
 *   <li>Total retained heap = number of pool threads × {@link #entriesPerThread()} ×
 *       {@link #valueSizeBytes()} bytes. With 16 pool threads, 100 entries each of 64 KB, total
 *       retention is 100 MB.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Heap pressure from retained ThreadLocal values.</strong> The retained values are
 *       permanent GC roots via the thread object; they grow the live set and reduce free heap for
 *       application objects; assert that the heap does not OOM within the test window.
 *   <li><strong>Increased GC pause time.</strong> A larger live set requires the GC to mark and
 *       copy more objects during each collection; pause times grow in proportion to the total
 *       retained bytes; assert that GC pauses remain within SLA.
 *   <li><strong>ThreadLocalMap growth and lookup latency.</strong> Each thread's
 *       {@code ThreadLocalMap} is an open-addressing hash table; with many entries its load factor
 *       increases and lookup time for any ThreadLocal (including the application's own) grows;
 *       assert that the framework's per-request ThreadLocal access latency does not become a
 *       bottleneck.
 *   <li><strong>Off-heap interactions.</strong> Some ThreadLocal values hold references to
 *       off-heap resources (database connections, output streams); retaining the ThreadLocal
 *       value prevents the off-heap resource from being released; assert that the resource pool
 *       detects the over-retention and reclaims leaked entries.
 *   <li><strong>Production failure mode:</strong> a servlet container thread serves a request,
 *       stores the request context in a {@code ThreadLocal}, and then throws an unhandled exception
 *       that bypasses the cleanup code in the {@code finally} block; the thread is returned to the
 *       pool with the context still set, and the next request on that thread picks up the previous
 *       request's context — causing data leakage between tenants.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Each Java thread owns a {@code ThreadLocalMap} — an open-addressing, linearly-probed hash
 * table keyed by {@code ThreadLocal} identity (using the ThreadLocal object's identity hash code).
 * Values are stored as {@code Entry} objects that weakly reference the {@code ThreadLocal} key but
 * strongly reference the value. If the {@code ThreadLocal} object itself is GC-collected (because
 * no code holds a strong reference to it), the entry's key becomes {@code null} and the entry
 * becomes a "stale entry". The JVM will clean up stale entries during subsequent {@code get()},
 * {@code set()}, or {@code remove()} calls, but never proactively.
 *
 * <p>This stressor keeps the {@code ThreadLocal} instances reachable (stored in a stressor-owned
 * list), so the entries are not stale — both the key and the value are strongly reachable. The
 * entries therefore never become candidates for cleanup regardless of GC frequency.
 *
 * <p>The interaction with virtual threads is important: virtual threads do not share a carrier's
 * {@code ThreadLocalMap}; each virtual thread has its own map. However, virtual threads use
 * {@code ScopedValue} rather than {@code ThreadLocal} in modern code; legacy code using
 * {@code ThreadLocal} on virtual threads still leaks as described, except that virtual threads may
 * be destroyed and recreated frequently (unlike pool threads), so the leak is bounded by the
 * lifetime of each virtual thread.
 *
 * <p>Combining this stressor with {@link ChaosHeapPressure} produces a cumulative heap-retention
 * scenario that is representative of a production system under a slow ThreadLocal leak: the
 * retained heap grows steadily while the application continues to allocate, eventually triggering
 * an OOM.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadLocalLeak(entriesPerThread = 50, valueSizeBytes = 32_768)
 * class ThreadLocalLeakTest {
 *   @Test
 *   void applicationDetectsAndAlertsOnThreadLocalRetention(ConnectionInfo info) { ... }
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
@Repeatable(ChaosThreadLocalLeak.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ThreadLocalLeakTranslator")
public @interface ChaosThreadLocalLeak {

  /**
   * @return number of ThreadLocal entries per pool thread
   */
  int entriesPerThread() default 100;

  /**
   * @return byte-array value size per entry
   */
  int valueSizeBytes() default 65_536;

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
   * @ChaosThreadLocalLeak(id = "primary",  probability = 0.001)
   * @ChaosThreadLocalLeak(id = "replica",  probability = 0.01)
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
    ChaosThreadLocalLeak[] value();
  }
}
