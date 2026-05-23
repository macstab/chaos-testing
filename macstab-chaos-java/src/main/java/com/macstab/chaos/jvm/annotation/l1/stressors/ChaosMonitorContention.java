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
 * Generates sustained monitor contention by running {@code N} threads that compete for a single
 * shared lock with configurable hold times, simulating the lock-contention profile of a
 * high-concurrency production workload with a bottleneck resource.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor spawns {@link #contendingThreadCount()} threads that all loop continuously, each
 * acquiring the same {@code synchronized} monitor, sleeping for {@link #lockHoldMs()} milliseconds
 * while holding the lock, then releasing and immediately re-entering the queue.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent spawns {@link #contendingThreadCount()} stressor threads. All threads share a
 *       single synthetic monitor object.
 *   <li>Each thread loops: it attempts to enter a {@code synchronized} block on the shared
 *       monitor. With {@code N} threads and one lock, at most one thread holds the lock at any
 *       time; the remaining {@code N-1} threads are in the BLOCKED state waiting for the lock.
 *   <li>The holding thread sleeps for {@link #lockHoldMs()} milliseconds (simulating a slow
 *       critical section), then releases the lock and immediately re-enters the contention queue.
 *   <li>The stressor maintains high monitor contention continuously. The JVM's
 *       {@code ThreadMXBean.getThreadInfo(id, 0).getBlockedCount()} counter for each stressor
 *       thread increments rapidly, and the total CPU cycles spent in lock admission inflate.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>OS scheduler pressure from lock contention.</strong> Threads waiting for a monitor
 *       enter the BLOCKED state; the OS must context-switch them in when the lock is released.
 *       High contention increases context-switch rate, which is visible in
 *       {@code /proc/[pid]/status} as {@code voluntary_ctxt_switches}. Assert that the
 *       application's own lock-sensitive operations complete within their timeout under this
 *       added scheduler pressure.
 *   <li><strong>Biased-lock revocation cost.</strong> When a high-contention lock is detected by
 *       the JVM, it revokes biased locking for that monitor. Revocation requires a safepoint. The
 *       stressor lock will trigger revocation; assert that the additional safepoints do not push
 *       latency over SLA.
 *   <li><strong>Application lock fairness issues.</strong> High background contention on the JVM's
 *       lock admission queue can cause application threads waiting for unrelated monitors to
 *       experience increased scheduling latency; assert that your application's critical-path
 *       operations are not starved by the background contention.
 *   <li><strong>Thread-dump readability.</strong> With many threads in BLOCKED state, thread dumps
 *       become harder to parse; assert that your alerting pipeline can identify the root-cause
 *       lock owner even under extreme contention.
 *   <li><strong>Production failure mode:</strong> a singleton resource (connection pool, shared
 *       cache, rate limiter) protected by a {@code synchronized} method under unexpectedly high
 *       concurrency exhibits exactly this pattern — most threads are blocked, throughput collapses,
 *       and latency spikes to the hold time times the queue depth.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Java monitors use a tiered locking strategy in HotSpot: a biased lock (single-thread fast
 * path), a lightweight lock (CAS-based, low-contention fast path), and a heavyweight inflated
 * lock (OS mutex, high-contention slow path). The stressor's continuous multi-thread contention
 * forces the monitor to inflate immediately to a heavyweight lock, bypassing the lighter fast
 * paths. The inflated lock is backed by an OS mutex or futex, so waiting threads are descheduled
 * by the OS and only rescheduled when the lock is released.
 *
 * <p>Monitor contention appears in profilers as time spent in the {@code __futex_wait} syscall
 * (Linux) or {@code pthread_cond_wait} (macOS). Async profilers sampling thread stacks in BLOCKED
 * state will show a large fraction of threads stuck at the lock admission point. Tools like
 * {@code -XX:+PrintContendedLocks} or JFR's {@code jdk.JavaMonitorEnter} event stream will record
 * each contention event.
 *
 * <p>The stressor's contention is deliberately isolated to a synthetic monitor that has no
 * application meaning. Application monitors are unaffected. The stressor raises the global
 * OS-scheduler pressure (more context switches, more futex wait/wake cycles) without creating
 * a dependency on any application lock. This lets tests measure whether the application's own
 * locking is correctly sized for the observed scheduler load.
 *
 * <p>Combining this stressor with {@link ChaosDeadlock} on the same test class is valid: the
 * deadlock stressor creates permanently blocked threads while the monitor-contention stressor
 * creates rapidly cycling contending threads. Together they exercise both static (deadlocked) and
 * dynamic (contending) lock pressure simultaneously.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosMonitorContention(contendingThreadCount = 16, lockHoldMs = 100)
 * class MonitorContentionTest {
 *   @Test
 *   void connectionPoolDoesNotStarveUnderHighContention(ConnectionInfo info) { ... }
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
@Repeatable(ChaosMonitorContention.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.MonitorContentionTranslator")
public @interface ChaosMonitorContention {

  /**
   * @return per-thread lock-hold duration in ms
   */
  long lockHoldMs() default 50L;

  /**
   * @return number of contending threads (>= 2)
   */
  int contendingThreadCount() default 8;

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
   * @ChaosMonitorContention(id = "primary",  probability = 0.001)
   * @ChaosMonitorContention(id = "replica",  probability = 0.01)
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
    ChaosMonitorContention[] value();
  }
}
