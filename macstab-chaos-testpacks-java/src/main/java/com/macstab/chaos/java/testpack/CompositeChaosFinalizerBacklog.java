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
 * <p>Floods the JVM {@code Finalizer} thread queue by creating and abandoning objects with slow
 * finalisers, backing up the single-threaded ReferenceHandler and preventing timely release of
 * finaliser-protected resources such as file descriptors and native memory.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code FINALIZER_BACKLOG} stressor via the JVM chaos agent. The stressor creates
 * {@link #objectCount()} objects whose {@code finalize()} methods sleep briefly, producing a queue
 * longer than the single Finalizer thread can drain. In production, finaliser backlogs accumulate
 * when object creation rates exceed the Finalizer thread's throughput — common in applications that
 * use streams opened with {@code FileInputStream} or legacy SSL implementations.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * A deep finaliser backlog causes memory to grow because finalisation-pending objects cannot be
 * reclaimed. It also causes file-descriptor and native-memory leaks. The Finalizer thread is a
 * global JVM resource; a stalled finaliser queue affects all subsystems that rely on finalisation.
 *
 * <h2>Industry references</h2>
 *
 * <p>Joshua Bloch, "Effective Java" (3rd ed.), Item 8: "Avoid finalizers and cleaners" details the
 * risks of finaliser queues. The single-threaded {@code FinalizerThread} design is documented in
 * the JDK source ({@code java.lang.ref.Finalizer}).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosFinalizerBacklog(objectCount = 10000)
 * class FinalizerBacklogTest {
 *   @Test
 *   void fdLeakDetectedWhenFinalizerQueueOverflows(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosFinalizerBacklog.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.FinalizerBacklogComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosFinalizerBacklog {

  /**
   * Number of finaliser-carrying objects to enqueue.
   *
   * @return object count; default 10000
   */
  int objectCount() default 10_000;

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
    CompositeChaosFinalizerBacklog[] value();
  }
}
