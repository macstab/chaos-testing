/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.pthread_create;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * After {@link #successesBeforeFailure} successful {@code pthread_create} calls, injects {@code
 * EAGAIN} on every subsequent call, causing the calling code to observe a persistent
 * insufficient-resources failure that models the system thread-count ceiling being reached.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EAGAIN},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code pthread_create} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code pthread_create}
 *       call returns {@code EAGAIN} directly (pthread_create returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EAGAIN} (11); no thread is created.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EAGAIN}; assert that the application checks the return value (pthread_create
 *       returns the error code directly, not -1; it does not set {@code errno}) and reduces the
 *       thread pool size or applies load-shedding rather than treating EAGAIN as permanent.
 *   <li>FAIL_AFTER models the thread-count ceiling: the pool starts N threads successfully; the
 *       (N+1)th create attempt exhausts the system thread limit (RLIMIT_NPROC or threads-max); all
 *       subsequent creates fail — assert that the pool degrades gracefully to N-thread capacity and
 *       queues work rather than dropping tasks.
 *   <li>Assert that the application does not retry pthread_create-EAGAIN in a tight loop without
 *       waiting for an existing thread to exit; EAGAIN self-heals when threads terminate and the
 *       kernel reclaims their task_struct entries.
 * </ul>
 *
 * Production failure mode: a thread pool grows dynamically under load; after N successful creates
 * the RLIMIT_NPROC ceiling is reached; the pool retries pthread_create-EAGAIN without waiting for
 * threads to exit, consuming CPU in a tight retry loop; the pool appears stuck at the ceiling and
 * logs EAGAIN thousands of times per second while not queuing any work.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the thread-count ceiling: the system allows N concurrent threads before
 * RLIMIT_NPROC or {@code /proc/sys/kernel/threads-max} is reached; subsequent creates fail with
 * EAGAIN until existing threads exit and the task count drops below the limit. pthread_create
 * returns the error code directly — checking {@code if (ret == -1)} silently misses EAGAIN (11).
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (thread pool growth
 * up to N threads); subsequent test methods exercise the EAGAIN-with-degraded-capacity path. Set
 * {@link #successesBeforeFailure} to the pool's maximum configured thread count to model the exact
 * moment the system ceiling is hit.
 *
 * <p>pthread_create-EAGAIN shares RLIMIT_NPROC with fork; a burst of forked subprocesses in another
 * part of the application can cause pthread_create to fail with EAGAIN even if the thread pool is
 * well below its configured maximum. Applications that mix fork and pthread_create must account for
 * this shared limit.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEagainFailAfter(successesBeforeFailure = 16)
 * class PthreadCreateThreadCeilingTest {
 *   @Test
 *   void threadPoolDegradesGracefullyAtCeilingAndQueuesWork(ConnectionInfo info) {
 *     // first 16 creates succeed; subsequent creates return EAGAIN;
 *     // verify pool degrades to 16 threads; work queued not dropped; no tight retry loop;
 *     // EAGAIN return value checked (not errno)
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the pool's
 * configured maximum thread count; values 4–200 cover most thread pool configurations; 0 means no
 * threads can be created (system already at ceiling at startup).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPthreadCreateEagainFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EAGAIN)
public @interface ChaosPthreadCreateEagainFailAfter {

  /**
   * @return number of matched calls allowed to succeed before failure begins ({@code >= 0})
   */
  long successesBeforeFailure() default 0L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-process
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosPthreadCreateEagainFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPthreadCreateEagainFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPthreadCreateEagainFailAfter[] value();
  }
}
