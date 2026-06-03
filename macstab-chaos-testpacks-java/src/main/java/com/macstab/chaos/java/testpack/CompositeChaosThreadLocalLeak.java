/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Leaks large values into per-thread {@code ThreadLocal} slots across a pool of threads,
 * gradually consuming heap memory that cannot be reclaimed as long as the threads stay alive —
 * simulating the off-heap-on-heap memory accumulation of a thread-pool that never clears its
 * request-scoped thread locals between requests.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code THREAD_LOCAL_LEAK} stressor via the JVM chaos agent. The stressor creates
 * {@link #threadCount()} threads, populates large byte arrays into their {@code ThreadLocal} maps,
 * and keeps the threads alive. In production, thread-local leaks occur when frameworks store
 * per-request state in thread locals and fail to call {@code ThreadLocal.remove()} at the end of
 * the request — common in Servlet filters and MDC implementations.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Heap fills gradually rather than suddenly. The application may run normally for hours before
 * the leak accumulates enough to trigger GC pressure, making it hard to diagnose without a heap
 * dump. Eventually, GC overhead rises and OOM occurs.
 *
 * <h2>Industry references</h2>
 *
 * <p>ThreadLocal leak via thread pools is documented in Kohsuke Kawaguchi's blog "The hidden
 * dangers of ThreadLocal" and the SLF4J MDC documentation. Both recommend explicit
 * {@code remove()} calls at request boundaries.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosThreadLocalLeak(threadCount = 50)
 * class ThreadLocalLeakTest {
 *   @Test
 *   void heapGrowthIsDetectedBeforeOom(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosThreadLocalLeak.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ThreadLocalLeakComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosThreadLocalLeak {

  /**
   * Number of threads in which to leak thread-local values.
   *
   * @return thread count; default 50
   */
  int threadCount() default 50;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosThreadLocalLeak[] value();
  }
}
