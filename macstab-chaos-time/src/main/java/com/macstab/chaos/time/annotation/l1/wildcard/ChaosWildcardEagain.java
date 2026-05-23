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
 * Injects {@code EAGAIN} into every interposed time syscall ({@code clock_gettime}, {@code nanosleep},
 * {@code usleep}), causing each to return {@code -1} with {@code errno = EAGAIN} as if a temporary
 * resource constraint prevented the operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code WILDCARD}, errno = {@code EAGAIN})
 * tuple. The {@code WILDCARD} selector matches all three interposed time syscalls simultaneously —
 * equivalent to applying {@link ChaosClockGettimeEagain}, {@link ChaosNanosleepEagain}, and
 * {@link ChaosUsleepEagain} in a single annotation. No runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted call to any of the three syscalls a Bernoulli trial with probability
 *       {@link #probability} is conducted independently.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EAGAIN}
 *       without performing any real work.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EAGAIN} is not a documented POSIX result of {@code clock_gettime} or
 *       {@code nanosleep}; applications that check only for {@code EINTR} or {@code EINVAL} will
 *       treat this as an unexpected error, exercising their default error-handling branch.
 *   <li>Applications that blindly loop on any non-zero return from time syscalls will busy-spin
 *       indefinitely under this injection, revealing the absence of a proper error-code check.
 *   <li>Assert that the application treats unrecognised errno values from time syscalls as fatal
 *       programming errors and either aborts with a clear message or propagates a structured
 *       exception rather than entering a retry loop.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from time syscalls is not a standard kernel behaviour on Linux;
 * its appearance signals a misconfigured or custom kernel, or a seccomp policy that translates
 * disallowed syscalls to {@code EAGAIN} rather than {@code ENOSYS} or {@code EPERM}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EAGAIN} is normally associated with non-blocking I/O and resource limits
 * ({@code RLIMIT_NPROC}, futex contention) rather than time syscalls. Neither {@code clock_gettime}
 * nor {@code nanosleep} nor {@code usleep} returns {@code EAGAIN} in any standard Linux kernel
 * code path. This injection therefore exercises the "unknown errno" handling posture of the
 * application — the path that executes when an error is received that the developer never
 * anticipated.
 *
 * <p>Some seccomp-bpf policies are configured to return {@code EAGAIN} for all unrecognised syscall
 * numbers as a denial strategy that appears less suspicious than {@code ENOSYS}. A process running
 * under such a policy would receive {@code EAGAIN} from any time syscall that the seccomp filter
 * has not explicitly allowed. This injection simulates that outcome without requiring a custom
 * seccomp profile in the test environment.
 *
 * <p>The {@code WILDCARD} selector applies the injection to all three time syscalls simultaneously,
 * making it impossible for the application to fall back from one time interface to another. Code
 * that catches {@code EAGAIN} on {@code nanosleep} and retries via {@code usleep} will encounter
 * the same error, fully exercising the no-fallback-available code path.
 *
 * <p>Sibling per-syscall annotations ({@link ChaosClockGettimeEagain}, {@link ChaosNanosleepEagain},
 * {@link ChaosUsleepEagain}) allow targeted injection to a single syscall when a narrower test
 * scope is needed.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosWildcardEagain(probability = 0.001)
 * class WildcardEagainTest {
 *   @Test
 *   void applicationHandlesUnexpectedEagainAcrossAllTimeSyscalls(ConnectionInfo info) {
 *     // assert that no busy-spin occurs and that a structured error is emitted
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEagain
 * @see ChaosNanosleepEagain
 * @see ChaosUsleepEagain
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosWildcardEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.WILDCARD, errno = TimeErrno.EAGAIN)
public @interface ChaosWildcardEagain {

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
   * @ChaosWildcardEagain(id = "primary",  probability = 0.001)
   * @ChaosWildcardEagain(id = "replica",  probability = 0.01)
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
    ChaosWildcardEagain[] value();
  }
}
