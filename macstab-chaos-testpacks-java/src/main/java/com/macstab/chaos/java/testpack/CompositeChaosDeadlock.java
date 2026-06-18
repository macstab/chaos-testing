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
 * <p>Injects a permanent circular monitor deadlock among {@link #threadCount()} synthetic daemon
 * threads for the duration of the test. The deadlocked threads are immediately detectable via
 * {@code ThreadMXBean.findDeadlockedThreads()}.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code DEADLOCK} stressor scenario via the JVM chaos agent. The agent spawns {@code
 * threadCount} synthetic threads in a lock-acquisition ring: thread 0 holds lock 0 and waits for
 * lock 1, thread 1 holds lock 1 and waits for lock 2, …, thread N-1 holds lock N-1 and waits for
 * lock 0. All threads remain in the BLOCKED state for the lifetime of the rule.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * A real deadlock among application threads causes zero forward progress on the deadlocked code
 * paths and typically requires a JVM restart to recover. Thread-pool exhaustion may follow if the
 * deadlocked threads belong to a shared pool. Monitoring that does not detect deadlocks will
 * silently lose the affected operations.
 *
 * <h2>Industry references</h2>
 *
 * <p>Monitor deadlock detection via {@code ThreadMXBean} is described in the Java SE specification
 * (java.lang.management package Javadoc). Brian Goetz, "Java Concurrency in Practice" §10.1
 * enumerates the three necessary conditions (mutual exclusion, hold-and-wait, circular wait) that
 * this stressor satisfies by construction.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosDeadlock(threadCount = 2)
 * class DeadlockMonitoringTest {
 *   @Test
 *   void healthCheckReportsDegradedOnDeadlock(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosDeadlock.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.DeadlockComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosDeadlock {

  /**
   * Number of synthetic threads to deadlock in a circular lock-acquisition ring. Minimum 2.
   *
   * @return participant count; default 2
   */
  int threadCount() default 2;

  /**
   * Container id to target. Empty string applies the scenario to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosDeadlock[] value();
  }
}
