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
 * <p>Sustains high monitor contention by spawning {@link #threadCount()} threads that continuously
 * compete for a single synthetic lock, inflating OS-scheduler pressure and biased-lock revocation
 * overhead throughout the test.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code MONITOR_CONTENTION} stressor via the JVM chaos agent. The stressor threads
 * enter and exit the shared {@code synchronized} block in a tight loop, forcing the lock to inflate
 * to a heavyweight OS mutex immediately. In production, this pattern occurs when a singleton
 * resource (connection pool, rate limiter, cache) protected by a {@code synchronized} method is hit
 * by more concurrency than it was designed for.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Application threads waiting for unrelated monitors experience increased scheduling latency due to
 * the elevated futex/context-switch rate. The application remains functional but latency P99/P999
 * widens. Biased-lock revocation safepoints add additional stop-the-world micro-pauses.
 *
 * <h2>Industry references</h2>
 *
 * <p>HotSpot monitor inflation and biased-lock revocation are documented in "Inside the Java
 * Virtual Machine" (Venners, 2nd ed.) §20.4. Linux perf can expose the resulting futex contention
 * via {@code perf stat -e 'syscalls:sys_enter_futex'}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosMonitorContention(threadCount = 4)
 * class MonitorContentionTest {
 *   @Test
 *   void connectionPoolDoesNotStarveUnderHighContention(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosMonitorContention.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.MonitorContentionComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosMonitorContention {

  /**
   * Number of threads competing for the shared synthetic monitor.
   *
   * @return thread count; default 4
   */
  int threadCount() default 4;

  /**
   * Per-thread lock-hold duration in milliseconds.
   *
   * @return hold duration in ms; default 50
   */
  long lockHoldMs() default 50L;

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
    CompositeChaosMonitorContention[] value();
  }
}
