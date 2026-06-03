/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

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
 * <p>Every {@code pthread_create()} call fails with {@code EAGAIN}, simulating a thread-pool or
 * OS-thread-count exhaustion. From the application's perspective the runtime can no longer create
 * new threads: request-handler threads cannot be allocated, background timers stall, and blocking
 * I/O paths that rely on dedicated threads are stuck. Applications that do not bound their thread
 * pools or that silently absorb {@code EAGAIN} without emitting metrics will appear hung rather
 * than degraded.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN,
 * toxicity)} via libchaos-process. In production this happens when a JVM or native thread pool is
 * not bounded: a traffic spike causes the runtime to spawn more threads than the kernel allows,
 * and subsequent {@code pthread_create()} calls return {@code EAGAIN}. Most JVM runtimes translate
 * {@code EAGAIN} into an {@code OutOfMemoryError: unable to create new native thread}.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * At {@code toxicity = 0.9} nearly every thread-creation attempt fails. Executor services
 * reject new tasks with {@code RejectedExecutionException}; reactive event loops lose worker
 * threads; connection pools cannot expand. Without circuit-breaking or bounded-pool enforcement,
 * the service enters a cascading failure. Operator intervention is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>Thread-pool exhaustion as a root cause of {@code OutOfMemoryError: unable to create new
 * native thread} is documented in the OpenJDK bug tracker and in the Oracle Java SE
 * troubleshooting guide. The pattern of misconfigured Netty or Tomcat thread pools hitting the OS
 * limit appears frequently in production post-mortems from large-scale Java deployments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosThreadPoolExhaustion(toxicity = 0.9)
 * class ThreadPoolExhaustionTest {
 *   @Test
 *   void executorRejectsGracefullyAndEmitsMetric() {
 *     // assert: RejectedExecutionException surfaced; metric emitted; no silent hang
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosThreadPoolExhaustion.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.ThreadPoolExhaustionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosThreadPoolExhaustion {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code EAGAIN} fires on each {@code pthread_create()}
   * call. Defaults to {@code 0.9} (nine in ten thread-creation attempts fail).
   */
  double toxicity() default 0.9;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosThreadPoolExhaustion[] value();
  }
}
