/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.wildcard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.time.annotation.l1.TimeErrnoBinding;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * Injects {@code EPERM} into every interposed time syscall ({@code clock_gettime}, {@code
 * nanosleep}, {@code usleep}), causing each to return {@code -1} with {@code errno = EPERM} as if
 * the process lacked permission to perform the time operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code WILDCARD}, errno = {@code
 * EPERM}) tuple. The {@code WILDCARD} selector matches all three interposed time syscalls
 * simultaneously — equivalent to applying {@link ChaosClockGettimeEperm}, {@link
 * ChaosNanosleepEperm}, and {@link ChaosUsleepEperm} in a single annotation. No runtime
 * selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted call to any of the three syscalls a Bernoulli trial with probability
 *       {@link #probability} is conducted independently.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EPERM}
 *       without performing any real work.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every time-related call may return {@code EPERM}; the application must treat it as a
 *       non-retriable capability failure and degrade gracefully.
 *   <li>Profiling agents, metrics collectors, and APM tracers that rely on high-resolution time may
 *       disable themselves when all time syscalls fail with permission denied.
 *   <li>Assert that the application detects the capability failure at startup or on first use,
 *       selects a fallback behavior, and does not crash or busy-spin.
 * </ul>
 *
 * <p>In production, simultaneous {@code EPERM} across all time syscalls signals a severely hardened
 * seccomp profile that blocks all time-related operations — unusual but possible in
 * ultra-locked-down container environments.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code EPERM} wildcard simulates the scenario of a container that has had all time
 * syscalls removed from its seccomp allowlist. This is an extreme hardening posture that some
 * security-sensitive workloads apply (e.g. cryptographic operations that must not rely on system
 * time to avoid timing side channels). In this environment, any time-dependent logic must either be
 * removed or receive time from a privileged sidecar process.
 *
 * <p>Injecting {@code EPERM} across all three time calls exercises the capability-detection paths
 * of all time-using libraries simultaneously. A JVM that cannot read the clock will typically fall
 * back to a lower-resolution OS interface; profilers that cannot sleep will either stop or
 * busy-poll. Testing this scenario prevents production regressions when the seccomp profile is
 * tightened.
 *
 * <p>Sibling per-syscall annotations ({@link ChaosClockGettimeEperm}, {@link ChaosNanosleepEperm},
 * {@link ChaosUsleepEperm}) allow targeted injection. Use the wildcard form for system-wide
 * capability-failure testing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosWildcardEperm(probability = 0.01)
 * class WildcardEpermTest {
 *   @Test
 *   void applicationDegradesGracefullyWhenAllTimeSyscallsDenied(ConnectionInfo info) {
 *     // assert that the application selects fallbacks and does not crash
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEperm
 * @see ChaosNanosleepEperm
 * @see ChaosUsleepEperm
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosWildcardEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.WILDCARD, errno = TimeErrno.EPERM)
public @interface ChaosWildcardEperm {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-time
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosWildcardEperm(id = "primary",  probability = 0.001)
   * @ChaosWildcardEperm(id = "replica",  probability = 0.01)
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
    ChaosWildcardEperm[] value();
  }
}
