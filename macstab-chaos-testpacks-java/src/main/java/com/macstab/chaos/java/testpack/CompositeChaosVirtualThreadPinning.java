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
 * <p>Pins virtual-thread carrier threads by holding them inside a {@code synchronized} block for
 * {@link #durationMs()} milliseconds, starving the carrier-thread pool and preventing unmounted
 * virtual threads from being scheduled.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code VIRTUAL_THREAD_CARRIER_PINNING} stressor via the JVM chaos agent. The agent
 * acquires {@code synchronized} locks on synthetic objects inside carrier threads so they cannot
 * unmount virtual threads that block. In production, carrier pinning occurs whenever a virtual
 * thread executes a {@code synchronized} block that contains a blocking call — a common mistake
 * when migrating platform-thread codebases to virtual threads.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Virtual threads queued behind pinned carriers cannot make progress. The effective parallelism of
 * the application drops to (carrier pool size − pinned count). Applications that assume unlimited
 * virtual-thread concurrency will stall. Symptoms are easy to detect via JFR
 * {@code jdk.VirtualThreadPinned} events.
 *
 * <h2>Industry references</h2>
 *
 * <p>JEP 425 (Virtual Threads) §"Pinning" explains the constraint: a virtual thread that blocks
 * inside a {@code synchronized} block cannot unmount. Project Loom documentation warns against
 * using {@code synchronized} in I/O-bound virtual-thread code.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosVirtualThreadPinning(durationMs = 1000)
 * class VirtualThreadPinningTest {
 *   @Test
 *   void requestsCompleteEvenWithPinnedCarriers(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosVirtualThreadPinning.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.VirtualThreadPinningComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosVirtualThreadPinning {

  /**
   * How long each carrier thread is pinned per cycle, in milliseconds.
   *
   * @return pin duration in ms; default 1000
   */
  long durationMs() default 1000L;

  /**
   * Number of carrier threads to pin simultaneously.
   *
   * @return pinned thread count; default 4
   */
  int pinnedThreadCount() default 4;

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
    CompositeChaosVirtualThreadPinning[] value();
  }
}
