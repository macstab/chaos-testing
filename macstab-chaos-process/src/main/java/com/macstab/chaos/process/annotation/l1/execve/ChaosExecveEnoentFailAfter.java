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
 * {@code ENOENT} on every subsequent call, simulating a binary-disappearance event that takes
 * effect after a bounded number of successful process image replacements.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code ENOENT},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is libchaos-process's counter-gated effect: the first
 * {@link #successesBeforeFailure} matched calls succeed normally; every call after that returns
 * {@code -1} with {@code errno = ENOENT} until the rule is removed. This models scenarios where
 * a binary is deleted, a volume is unmounted, or a symlink target is removed mid-run after the
 * helper binary has already been successfully invoked a number of times.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule atomic counter of successful {@code execve} calls.</li>
 *   <li>Once the counter reaches {@link #successesBeforeFailure}, it trips and every subsequent
 *       intercepted call sets {@code errno = ENOENT} and returns {@code -1} without executing.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 2,
 *       {@code strerror}: "No such file or directory"; the counter remains tripped until removal.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} {@code execve} calls succeed; subsequent calls
 *       fail with {@code ENOENT} — the application must detect the binary-gone transition and
 *       fail gracefully rather than assuming the binary was never installed.</li>
 *   <li>Applications that use helper binaries for optional features must handle mid-run binary
 *       disappearance: assert that the feature is disabled cleanly when the binary path returns
 *       {@code ENOENT} after previously succeeding, rather than logging a confusing "binary
 *       not found" error that suggests a deployment problem on a running system.</li>
 *   <li>Assert that the application does not use the initial exec success as permanent proof that
 *       the binary exists — subsequent exec calls must not skip the return-value check based on
 *       a cached "binary is present" assumption established during startup.</li>
 * </ul>
 * Production failure mode: a shared volume providing a helper binary is unmounted during a
 * rolling deployment; existing containers that previously exec'd the binary from the volume
 * successfully now see {@code ENOENT}; the application continues accepting requests that require
 * the helper but silently fails to invoke it, producing incorrect output without surfacing the
 * binary-gone condition as an operational alert.
 *
 * <h2>Deep technical dive</h2>
 * <p>The FAIL_AFTER effect for {@code ENOENT} captures the binary-disappearance failure mode
 * that cannot be tested with the probabilistic ERRNO effect alone: if the binary is truly gone
 * from the path, every exec call will fail — not just a random fraction. The N-success-then-fail
 * pattern simulates the transition point where the binary becomes unavailable mid-run, with N
 * representing the number of successful invocations before the volume unmount or file deletion.
 *
 * <p>The distinction from the probabilistic variant is operationally significant: random ENOENT
 * (from the ERRNO effect) tests error handling for transient VFS lookup failures; permanent
 * ENOENT (from the FAIL_AFTER effect after threshold) tests graceful degradation when a
 * dependency becomes permanently unavailable. Applications must implement both: transient
 * handling (retry or fallback for random failures) and permanent degradation (disable the
 * feature when the binary is persistently absent).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEnoentFailAfter(successesBeforeFailure = 3)
 * class BinaryDisappearanceTest {
 *   @Test
 *   void applicationDegradesToInternalFallbackWhenHelperBinaryDisappears(ConnectionInfo info) {
 *     // verify feature disabled cleanly after ENOENT; no "binary not found" false alarm
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * times the application invokes the helper binary during the healthy phase of the test scenario;
 * zero is useful for testing startup-time binary validation.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveEnoentFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.ENOENT)
public @interface ChaosExecveEnoentFailAfter {

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
   * @ChaosExecveEnoentFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveEnoentFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveEnoentFailAfter[] value();
  }
}
