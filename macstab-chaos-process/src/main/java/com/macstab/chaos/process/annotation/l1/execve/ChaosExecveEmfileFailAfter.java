/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

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
 * Allows the first {@link #successesBeforeFailure} {@code execve} calls to succeed, then injects
 * {@code EMFILE} on every subsequent call, simulating per-process file descriptor exhaustion that
 * causes exec failures after a bounded number of successful process image replacements.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code EMFILE},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is libchaos-process's counter-gated effect: the first
 * {@link #successesBeforeFailure} matched calls succeed normally; every call after that returns
 * {@code -1} with {@code errno = EMFILE} until the rule is removed. This models progressive
 * file descriptor exhaustion scenarios where the process accumulates leaks over time until the
 * fd table is too full to allocate the slot needed to open the exec target binary.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule atomic counter of successful {@code execve} calls.</li>
 *   <li>Once the counter reaches {@link #successesBeforeFailure}, it trips and every subsequent
 *       intercepted call sets {@code errno = EMFILE} and returns {@code -1} without executing.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 24,
 *       {@code strerror}: "Too many open files"; the counter remains tripped until removal.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} {@code execve} calls succeed; subsequent calls
 *       fail with {@code EMFILE} — the application must detect the fd-exhaustion transition and
 *       treat it as a recoverable resource error (close leaked fds or raise the limit) rather
 *       than a permanent binary-unavailability condition.</li>
 *   <li>Process managers that spawn helpers in response to requests must handle the fd-table-full
 *       scenario by stopping new spawn attempts, logging the current fd count, and surfacing an
 *       fd-leak diagnostic rather than queuing spawn requests that will all fail with EMFILE.</li>
 *   <li>Assert that the application correctly distinguishes exec-EMFILE (fd table full — check
 *       for leaks) from exec-ENOENT (binary not found — check deployment) in its diagnostic;
 *       the remediation operator actions are entirely different for these two errors.</li>
 * </ul>
 * Production failure mode: a long-running server process spawns helper subprocesses for each
 * request but leaves open file descriptors across each fork+exec iteration due to missing
 * {@code FD_CLOEXEC} flags or incorrect {@code close} calls; after N successful execs the
 * process's fd table is exhausted and all subsequent helper invocations fail with {@code EMFILE},
 * silently breaking the feature that depends on the helper.
 *
 * <h2>Deep technical dive</h2>
 * <p>The FAIL_AFTER model for exec-EMFILE accurately captures the progressive fd-leak scenario:
 * each successful exec that leaks fds brings the process closer to its {@code RLIMIT_NOFILE}
 * limit; after exactly N execs the limit is crossed and all subsequent execs fail. The
 * deterministic threshold makes the fd-leak path reproducible in tests, enabling assertions
 * against the exact exec count at which EMFILE first occurs.
 *
 * <p>A key diagnostic tool for exec-EMFILE is the process's current fd count at the time of
 * the failure. Applications should log {@code /proc/self/fd} count alongside the {@code EMFILE}
 * error from exec. The threshold value can be calibrated by running the application under the
 * FAIL_AFTER annotation with increasing {@link #successesBeforeFailure} values until the
 * annotation's threshold coincides with the application's actual fd-leak rate.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEmfileFailAfter(successesBeforeFailure = 100)
 * class FdLeakExhaustionTest {
 *   @Test
 *   void applicationSurfacesFdLeakDiagnosticWhenExecEmfileTriggers(ConnectionInfo info) {
 *     // verify EMFILE from exec triggers fd count log and fd-leak alert, not binary-missing error
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of exec calls before fd exhaustion based on the known leak rate and the process's
 * {@code RLIMIT_NOFILE}; use zero to test startup-time fd-limit validation.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveEmfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.EMFILE)
public @interface ChaosExecveEmfileFailAfter {

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
   * @ChaosExecveEmfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveEmfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveEmfileFailAfter[] value();
  }
}
