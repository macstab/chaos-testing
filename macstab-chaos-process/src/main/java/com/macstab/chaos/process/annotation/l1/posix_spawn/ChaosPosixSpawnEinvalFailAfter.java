/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawn;

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
 * After {@link #successesBeforeFailure} successful {@code posix_spawn} calls, injects
 * {@code EINVAL} on every subsequent call, causing the calling code to observe an invalid-argument
 * failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code EINVAL},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code posix_spawn}
 *       call returns {@code EINVAL} directly (POSIX spawn returns the error code, not -1).</li>
 *   <li>The calling code receives: return value {@code EINVAL} (22); no child process is created;
 *       the pid output parameter is not set to a valid value.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EINVAL}; assert that the application treats EINVAL as a permanent, non-
 *       retryable programming error and escalates to a configuration alert rather than retrying.</li>
 *   <li>FAIL_AFTER is useful for modelling spawn attribute changes that take effect after a number
 *       of successful spawns — e.g. a dynamic attribute builder that introduces an invalid
 *       scheduling parameter after N calls; assert that the application detects the configuration
 *       regression at the threshold and does not mask it with silent retries.</li>
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold EINVAL — POSIX does not define the pid value when spawn fails.</li>
 * </ul>
 * Production failure mode: a process manager uses dynamic spawn attributes; a library version
 * upgrade changes the encoding of a scheduler flag, producing attributes that the kernel rejects
 * with EINVAL after N successful spawns (where N is the number of spawns before the updated
 * library code path is triggered); the manager retries EINVAL indefinitely, filling logs.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models a spawn attribute regression that manifests after N successful calls.
 * Unlike EAGAIN or ENOMEM (transient resource failures), EINVAL is a permanent programming error.
 * POSIX spawn returns the error code directly — checking {@code if (ret < 0)} misses EINVAL (22).
 * The counter does not reset between test methods at class scope, allowing a test suite to verify
 * correct attribute values in early methods and the EINVAL escalation path in later methods.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEinvalFailAfter(successesBeforeFailure = 10)
 * class PosixSpawnAttributeRegressionTest {
 *   @Test
 *   void managerEscalatesOnEinvalAndDoesNotRetry(ConnectionInfo info) {
 *     // first 10 spawns succeed; subsequent spawns return EINVAL;
 *     // verify configuration alert raised; no retry loop; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * spawns before the attribute regression code path is triggered; values 1–50 cover most
 * attribute-change scenarios; 0 means the first spawn receives EINVAL.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnEinvalFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.EINVAL)
public @interface ChaosPosixSpawnEinvalFailAfter {

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
   * @ChaosPosixSpawnEinvalFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnEinvalFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnEinvalFailAfter[] value();
  }
}
