/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * After {@link #successesBeforeFailure} successful process-management syscall invocations across
 * all intercepted families, injects {@code EAGAIN} on every subsequent call, modelling a uid
 * process-count ceiling scenario where the uid process limit is hit after N successful process
 * and thread creations, causing all subsequent process-management operations to return
 * "Resource temporarily unavailable" permanently until load is shed.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EAGAIN},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
 * process-management calls (across all families — fork, execve, posix_spawn, pthread_create,
 * waitpid) succeed, then the counter trips permanently and every subsequent call returns the error
 * code until the rule is removed. Compile-time safety: invalid selector/errno/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter shared across all intercepted syscall
 *       families; the counter does not reset automatically between test methods when the annotation
 *       is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent process-management
 *       call returns {@code -1} (or the errno value directly for pthread_create and posix_spawn)
 *       with {@code errno = EAGAIN}.</li>
 *   <li>The calling code receives: {@code fork()} returns {@code -1} with {@code errno = EAGAIN}
 *       (11); {@code pthread_create}/{@code posix_spawn} return {@code EAGAIN} directly;
 *       {@code strerror(EAGAIN)}: "Resource temporarily unavailable".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return EAGAIN permanently; assert that the application's EAGAIN retry
 *       logic is bounded — an unbounded retry loop receiving EAGAIN from every process-management
 *       path simultaneously will spin indefinitely without recovering.</li>
 *   <li>FAIL_AFTER models the RLIMIT_NPROC ceiling: N process and thread creations succeed;
 *       the uid process count hits the kernel limit; all subsequent fork and pthread_create calls
 *       return EAGAIN — assert that the application detects the ceiling, sheds load by rejecting
 *       new requests, and alerts operators rather than continuing to spawn.</li>
 *   <li>Assert that the application does not treat EAGAIN from WILDCARD FAIL_AFTER as identical
 *       to transient EAGAIN (ERRNO variant) — FAIL_AFTER EAGAIN is sustained and will not resolve
 *       without load reduction; the back-off strategy must be more aggressive than for transient
 *       pressure.</li>
 * </ul>
 * Production failure mode: a container under load continuously spawns worker threads and
 * subprocesses; when the uid process count hits RLIMIT_NPROC all fork and pthread_create calls
 * return EAGAIN simultaneously; the application's per-path retry loops each spin independently
 * with short back-offs, each consuming scheduler cycles without releasing process slots; the
 * retry loops prevent the container from serving requests while maintaining the pressure.
 *
 * <h2>Deep technical dive</h2>
 * <p>The WILDCARD FAIL_AFTER counter is shared across all process-management families. A fork call,
 * a pthread_create call, and a posix_spawn call each consume one counter unit. The EAGAIN phase
 * begins when the combined process-management call traffic from all families exhausts the counter.
 * Set {@link #successesBeforeFailure} to the expected total count across all families during the
 * pre-ceiling phase — this is the number of threads and processes the application creates before
 * hitting the uid limit.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. The
 * first test method exercises the pre-ceiling phase (the application creates processes and threads
 * normally); subsequent test methods exercise the EAGAIN-ceiling phase (all process management
 * is blocked until load is shed). This enables sequential testing of the ceiling-hit and
 * load-shedding behaviors in separate test methods.
 *
 * <p>EAGAIN from WILDCARD FAIL_AFTER is semantically sustained: unlike transient EAGAIN (which
 * resolves when another process exits and releases its process table slot), FAIL_AFTER EAGAIN
 * persists until the rule is removed. Applications that implement EAGAIN back-off with a maximum
 * retry count will correctly escalate after exhausting retries; applications that retry indefinitely
 * will spin until the test timeout fires.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEagainFailAfter(successesBeforeFailure = 100)
 * class ProcessCeilingTest {
 *   @Test
 *   void applicationShedsLoadAndAlertsOperatorsOnSustainedEagain(ConnectionInfo info) {
 *     // first 100 process calls succeed; subsequent calls return EAGAIN permanently;
 *     // verify bounded retry; verify load shedding (request rejection); verify alert sent;
 *     // verify no infinite spin; verify different back-off than transient EAGAIN
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total number
 * of process-management calls (across all families) during the application's startup and
 * steady-state phase before the ceiling; values 20–500 cover typical workload sizes; 0 means
 * the ceiling is hit before the first process or thread can be created.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEagainFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EAGAIN)
public @interface ChaosWildcardEagainFailAfter {

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
   * @ChaosWildcardEagainFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEagainFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEagainFailAfter[] value();
  }
}
