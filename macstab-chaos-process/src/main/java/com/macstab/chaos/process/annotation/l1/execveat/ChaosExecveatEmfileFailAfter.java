/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execveat;

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
 * After {@link #successesBeforeFailure} successful {@code execveat} calls, injects {@code EMFILE}
 * on every subsequent call, causing the calling code to observe a per-process fd-table exhaustion
 * failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code EMFILE}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call fails until the rule is removed. This is
 * distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional delay).
 * Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter. Each {@code execveat} call that passes
 *       the counter check decrements the remaining budget; the counter does not reset automatically
 *       between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code execveat} call
 *       sets {@code errno = EMFILE} and returns {@code -1} without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 24, {@code strerror}: "Too many
 *       open files"; the process's fd table has reached {@code RLIMIT_NOFILE}; the {@code dirfd}
 *       must be closed explicitly since no close-on-exec processing occurs for failed execs.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = EMFILE}; assert that the application closes the
 *       {@code dirfd} on every failure and reports the current fd count in the diagnostic to enable
 *       operator diagnosis of the leak source.
 *   <li>Under FAIL_AFTER, each failed exec that does not close its dirfd leaks one fd slot and
 *       worsens the exhaustion — assert that the application does not enter a self-reinforcing loop
 *       where each failed exec attempt opens a fresh dirfd, fails with EMFILE, and leaks the dirfd,
 *       making the next attempt more likely to fail even earlier in the open phase.
 *   <li>Assert that the application distinguishes {@code EMFILE} (24, per-process fd table full,
 *       fixable by closing leaked fds in this process) from {@code ENFILE} (23, system-wide kernel
 *       file table exhausted, requires platform team action) — the remediation actions differ and
 *       the diagnostic must route to the correct operator runbook.
 * </ul>
 *
 * Production failure mode: a container runtime opens a {@code dirfd} to the container's rootfs
 * overlay for each {@code execveat(AT_EMPTY_PATH)} launch; leaked fds from previous failed launches
 * accumulate in the runtime's fd table; after N successful launches the table approaches {@code
 * RLIMIT_NOFILE}; subsequent exec attempts open a dirfd (consuming the last free slot), then fail
 * with {@code EMFILE} from the exec's internal binary-open, and leak the dirfd — triggering {@code
 * EMFILE} on the dirfd open itself on the very next attempt.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the progressive fd-leak accumulation pattern more accurately than
 * probabilistic ERRNO. Real {@code EMFILE} from {@code execveat} in container runtimes follows a
 * deterministic threshold: the runtime leaks N fds (one per failed exec from previous errors) until
 * the process's fd table reaches {@code RLIMIT_NOFILE}; then all subsequent exec attempts fail.
 * Setting {@link #successesBeforeFailure} to the observed exec count before the fd table fills
 * reproduces this threshold exactly.
 *
 * <p>The execveat + FAIL_AFTER interaction creates a self-reinforcing failure loop that is absent
 * from plain execve. The {@code AT_EMPTY_PATH} pattern requires the application to open the binary
 * fd (the {@code dirfd}) before calling exec. Under FAIL_AFTER, each of the first N successful
 * execs closes the dirfd via {@code FD_CLOEXEC}. After the counter trips, every subsequent call:
 * (1) opens a fresh dirfd (consuming one fd slot), (2) receives {@code EMFILE} from the interposer,
 * (3) must close the dirfd in the error path. Applications that omit step (3) leak one fd per
 * failed attempt, rapidly reaching the point where the dirfd open itself fails with {@code EMFILE}
 * — the application now cannot even start an exec attempt.
 *
 * <p>The threshold calibration can be derived from the application's observed steady-state exec
 * rate and its fd leak rate: if the application leaks 1 fd every 10 execs and {@code RLIMIT_NOFILE}
 * is 1024, the threshold is approximately 10,240 execs. For tests, a smaller threshold (10–200)
 * exercises the same failure mode without requiring thousands of exec calls.
 *
 * <p>The counter does not reset between test methods at class scope, allowing a test suite to
 * verify the "N successful exec" phase in early methods and the "fd table full, every exec fails"
 * phase in later methods. This mirrors the production incident timeline: the fd leak accumulates
 * over time and the failure occurs when the process hits the limit, not as random individual
 * events.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEmfileFailAfter(successesBeforeFailure = 100)
 * class ExecveatFdLeakAccumulationTest {
 *   @Test
 *   void runtimeClosesDirfdAndReportsFdCountOnEmfileAfterThreshold(ConnectionInfo info) {
 *     // first 100 execveat calls succeed; subsequent calls return EMFILE;
 *     // verify dirfd closed on each failure; fd count in diagnostic; no self-reinforcing loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> derive from the observed fd leak rate and {@code
 * RLIMIT_NOFILE}; for test purposes, values in the range 10–200 exercise the failure mode
 * efficiently; 0 means the fd table is full from the first exec attempt (tests the case where the
 * runtime starts with a nearly-full fd table from inherited fds).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveatEmfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.EMFILE)
public @interface ChaosExecveatEmfileFailAfter {

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
   * @ChaosExecveatEmfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveatEmfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveatEmfileFailAfter[] value();
  }
}
