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
 * Injects a thread leak into the target container's JVM by spawning a configurable number of
 * platform threads that loop indefinitely, consuming OS thread handles, kernel thread stacks, and
 * JVM-internal thread table entries for the duration of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor creates {@link #threadCount()} platform threads, all of which loop calling
 * {@code LockSupport.parkNanos()} to yield CPU while staying alive, and holds them for the
 * duration of the rule.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent creates {@link #threadCount()} {@code Thread} instances, names them with the
 *       {@link #namePrefix()} prefix followed by a sequential index, sets their
 *       {@link #daemon()} flag, and starts them.</li>
 *   <li>Each thread enters an infinite loop that parks itself for a short interval
 *       ({@code LockSupport.parkNanos()}), consuming negligible CPU but maintaining the OS kernel
 *       thread as alive. The thread never returns from its {@code run()} method unless
 *       interrupted.</li>
 *   <li>The JVM's internal thread table grows by {@link #threadCount()} entries. Each thread
 *       reserves a native OS stack (governed by {@code -Xss}; typically 512 KB–1 MB on 64-bit
 *       Linux) plus a JVM-side {@code JavaThread} struct.</li>
 *   <li>At cleanup, the agent interrupts all leaked threads, causing them to exit their park loops
 *       via {@code InterruptedException}. The JVM then releases the native stacks and removes the
 *       entries from its thread table.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Thread count metric spike.</strong> Monitoring dashboards that track
 *       {@code java.lang:type=Threading/ThreadCount} will show a sudden increase of
 *       {@link #threadCount()} threads; assert that the alerting threshold is set appropriately
 *       and that the alert fires within an acceptable detection window.
 *   <li><strong>Native stack reservation exhaustion.</strong> Each leaked thread consumes
 *       {@code -Xss} of virtual address space; with many threads, the process may exhaust
 *       addressable stack space or the OS's thread limit ({@code ulimit -u} on Linux); assert that
 *       the JVM throws {@code OutOfMemoryError: Unable to create new native thread} rather than
 *       crashing silently.
 *   <li><strong>OS scheduler overhead.</strong> Even parked threads must be maintained by the OS
 *       scheduler (wakeup timers, signal masks); with hundreds of leaked threads, scheduler
 *       overhead increases; assert that the application's request throughput does not degrade by
 *       more than an acceptable fraction.
 *   <li><strong>Thread-dump pollution.</strong> Operational tools that capture thread dumps
 *       ({@code jstack}, async-profiler) will include all leaked threads; assert that your
 *       incident-response runbook can filter out chaos-injected threads (identifiable by the
 *       {@link #namePrefix()}).
 *   <li><strong>Production failure mode:</strong> a framework that creates a new thread per
 *       request (or per scheduled task) without bounds will gradually exhaust the OS thread limit;
 *       the first symptom is an {@code OutOfMemoryError: Unable to create new native thread} on
 *       the request path, causing the server to stop accepting new connections.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Platform threads in HotSpot are 1:1 mapped to OS kernel threads (pthreads on Linux, Win32
 * threads on Windows). Creating a thread allocates: a native stack (mmap'd, lazy-committed on
 * Linux by default), a JVM-internal {@code JavaThread} object on the C heap, and an entry in the
 * JVM's thread list. The thread is registered with the OS scheduler and receives a thread ID.
 *
 * <p>The OS imposes limits on the number of threads per process ({@code /proc/sys/kernel/threads-max}
 * and the process's {@code RLIMIT_NPROC}) and per system (the same kernel parameter divided by
 * running processes). In containerised environments these limits are often set lower than on bare
 * metal; the stressor exercises what happens when the application approaches those limits.
 *
 * <p>Virtual threads (Java 21+) do not consume an OS thread per virtual thread; they share a pool
 * of carrier threads. This stressor creates platform threads, not virtual threads, so it is
 * effective regardless of whether the application uses virtual threads. To stress the virtual-thread
 * scheduler specifically, use {@link ChaosVirtualThreadCarrierPinning}.
 *
 * <p>The {@link #daemon()} flag controls behaviour at JVM shutdown: daemon threads are killed
 * automatically when all non-daemon threads have exited; non-daemon leaked threads ({@code daemon =
 * false}) will prevent the JVM from shutting down normally, extending the shutdown duration. For
 * most tests, leaving {@code daemon = true} (the default) is appropriate because it allows the
 * container to shut down cleanly after the test, but setting {@code daemon = false} explicitly
 * tests whether the container's shutdown hook (or Kubernetes graceful termination) can deal with
 * non-terminating threads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadLeak(threadCount = 200, namePrefix = "chaos-l1-leaked-", daemon = true)
 * class ThreadLeakTest {
 *   @Test
 *   void alertingDetectsThreadCountSpikeWithinTenSeconds(ConnectionInfo info) { ... }
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
@Repeatable(ChaosThreadLeak.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ThreadLeakTranslator")
public @interface ChaosThreadLeak {

  /**
   * @return number of threads to leak (> 0)
   */
  int threadCount() default 50;

  /**
   * @return name prefix for leaked threads
   */
  String namePrefix() default "chaos-l1-leaked-";

  /**
   * @return whether leaked threads are daemons (if false, they block JVM exit)
   */
  boolean daemon() default true;

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
   * @ChaosThreadLeak(id = "primary",  probability = 0.001)
   * @ChaosThreadLeak(id = "replica",  probability = 0.01)
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
    ChaosThreadLeak[] value();
  }
}
