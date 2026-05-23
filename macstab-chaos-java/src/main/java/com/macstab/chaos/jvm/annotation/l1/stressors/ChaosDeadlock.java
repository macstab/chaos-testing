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
 * Injects a permanent monitor deadlock into the target container's JVM by locking {@code N}
 * synthetic threads in a circular lock-acquisition cycle for the duration of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor creates a classical circular-wait deadlock cycle among {@link #participantCount()}
 * synthetic daemon threads, all of which remain permanently suspended in
 * {@code Object.wait()} / {@code synchronized} for the lifetime of the rule.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent spawns {@link #participantCount()} synthetic threads (minimum 2) and assigns each
 *       thread a unique monitor object.</li>
 *   <li>Each thread acquires its own monitor and then attempts to acquire the next thread's monitor,
 *       forming a ring: thread 0 holds lock 0, waits for lock 1; thread 1 holds lock 1, waits for
 *       lock 2; …; thread N-1 holds lock N-1, waits for lock 0.</li>
 *   <li>All threads block permanently in the BLOCKED state. The monitors are held for the entire
 *       duration of the test; no thread ever progresses or releases its lock.</li>
 *   <li>The JVM's built-in deadlock detector (accessible via
 *       {@code ThreadMXBean.findDeadlockedThreads()}) immediately reports all N threads as
 *       deadlocked.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>JVM deadlock detection triggers.</strong> Health-check endpoints that call
 *       {@code ThreadMXBean.findDeadlockedThreads()} will report a non-empty deadlock set; assert
 *       that the health check responds with a degraded status and that alerting fires.
 *   <li><strong>Thread-pool starvation if locks are shared.</strong> If the application's own code
 *       happens to use the same monitor objects (impossible with synthetic monitors, but illustrates
 *       the concern for real deadlocks), its threads would also block; with this stressor the
 *       synthetic monitors are isolated, so only the stressor threads are blocked. Tests can verify
 *       that the application's thread pool is not affected by a deadlock in a background subsystem.
 *   <li><strong>JVM shutdown delayed.</strong> With {@code daemon = false} (not the default), the
 *       deadlocked threads prevent JVM shutdown until they are interrupted; assert that the
 *       container's shutdown hook can force-terminate them.
 *   <li><strong>Heap pressure from retained monitors.</strong> Each synthetic thread retains its
 *       stack; with a large {@code participantCount}, the combined stack reservation can be
 *       significant; size accordingly.
 *   <li><strong>Production failure mode:</strong> a real deadlock between application threads (e.g.
 *       two service methods acquiring locks in opposite order) manifests identically — threads in
 *       the BLOCKED state, zero forward progress, and a deadlock report from
 *       {@code ThreadMXBean}. This stressor lets you validate that monitoring and alerting work
 *       before a real deadlock occurs in production.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>A deadlock requires three conditions to hold simultaneously: mutual exclusion (each monitor
 * is held by exactly one thread), hold and wait (each thread holds at least one monitor while
 * waiting for another), and circular wait (the lock-acquisition graph contains a cycle). The
 * stressor satisfies all three conditions by construction: each thread enters a
 * {@code synchronized} block on its own monitor and then blocks on a {@code synchronized} block on
 * the next thread's monitor. The cycle closes because thread N-1 waits for lock 0, which is held
 * by thread 0.
 *
 * <p>The JVM represents blocked threads in the {@code BLOCKED} thread state, not {@code WAITING}.
 * This is an important distinction: the blocked threads occupy an OS thread (they are not
 * unmounted as virtual threads would be), and they hold their allocated stack frames. For
 * platform threads (the default), each thread consumes approximately {@code -Xss} bytes of OS
 * stack space. With the default {@code participantCount} of 2 this is negligible; increasing to
 * hundreds of participants tests whether deadlock-detection overhead scales acceptably.
 *
 * <p>The {@code ThreadMXBean.findDeadlockedThreads()} method performs a graph search over all
 * blocked threads and their lock dependencies. It is O(N) in the number of threads. Some
 * monitoring agents run this check on a fixed schedule; this stressor lets you verify that the
 * agent correctly identifies and reports the deadlock cycle rather than silently discarding
 * deadlock events.
 *
 * <p>The stressor threads are named with a recognisable prefix so that they are easy to identify
 * in thread dumps ({@code jstack}, {@code kill -3}, or async-profiler snapshots) during debugging.
 * The synthetic monitors are plain {@code Object} instances with no application meaning; releasing
 * them at cleanup requires interrupting the stressor threads, which the agent does automatically
 * when the rule is removed.
 *
 * <p>Virtual-thread-based applications are partially immune: virtual threads that block on a
 * {@code ReentrantLock} (as opposed to a {@code synchronized} block) unmount from their carrier
 * thread, so a deadlock among virtual threads using {@code ReentrantLock} does not consume carrier
 * threads. However, if the application uses {@code synchronized} blocks (which pin the virtual
 * thread to its carrier), a deadlock there is just as severe as for platform threads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosDeadlock(participantCount = 3)
 * class DeadlockMonitoringTest {
 *   @Test
 *   void healthCheckReportsDegradedOnDeadlock(ConnectionInfo info) { ... }
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
@Repeatable(ChaosDeadlock.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DeadlockTranslator")
public @interface ChaosDeadlock {

  /**
   * @return number of threads to deadlock (>= 2)
   */
  int participantCount() default 2;

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
   * @ChaosDeadlock(id = "primary",  probability = 0.001)
   * @ChaosDeadlock(id = "replica",  probability = 0.01)
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
    ChaosDeadlock[] value();
  }
}
