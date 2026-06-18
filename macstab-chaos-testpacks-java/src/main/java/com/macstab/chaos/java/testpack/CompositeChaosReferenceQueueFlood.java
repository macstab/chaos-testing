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
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Floods the JVM {@code ReferenceHandler} thread's queue with {@link #objectCount()} weak
 * references, saturating the single-threaded reference processing path and delaying reclamation of
 * soft- and weak-referenced objects across the JVM.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code REFERENCE_QUEUE_FLOOD} stressor via the JVM chaos agent. The stressor creates
 * many {@code WeakReference} instances pointing to short-lived objects, triggering a large
 * reference enqueue batch on the next GC cycle. In production, reference-queue flooding occurs in
 * applications that use weak or soft reference caches with very high entry turnover without
 * bounding the cache size.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * The ReferenceHandler thread processes the queue asynchronously. While the queue is backed up,
 * soft references are not cleared promptly, which can prevent the GC from recovering memory it
 * would otherwise reclaim. The effect is transient: once the queue drains, the JVM recovers.
 *
 * <h2>Industry references</h2>
 *
 * <p>The JVM reference processing architecture (ReferenceHandler thread, reference queues, and
 * their interaction with GC) is documented in the JDK source ({@code java.lang.ref.Reference}). G1
 * GC concurrent reference processing (JEP 376) moved some of this work off-STW, but the enqueue
 * path remains single-threaded.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosReferenceQueueFlood(objectCount = 50000)
 * class ReferenceQueueFloodTest {
 *   @Test
 *   void softCacheClearsUnderMemoryPressure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosReferenceQueueFlood.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ReferenceQueueFloodComposer",
    severity = Severity.MILD)
public @interface CompositeChaosReferenceQueueFlood {

  /**
   * Number of weak references to enqueue in the reference handler's queue.
   *
   * @return reference count; default 50000
   */
  int objectCount() default 50_000;

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
    CompositeChaosReferenceQueueFlood[] value();
  }
}
