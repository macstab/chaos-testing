/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.clock_gettime;

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
 * Injects {@code EINVAL} into {@code clock_gettime(2)}, causing the call to return {@code -1} with
 * {@code errno = EINVAL} as if the kernel rejected an invalid or unsupported clock identifier.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, errno = {@code
 * EINVAL}) tuple. The tuple is safe by construction — {@code EINVAL} is a documented POSIX result
 * of {@code clock_gettime(2)} when the {@code clockid} argument is unknown, not supported by the
 * kernel build, or the process lacks the capability required for that clock. No runtime
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
 *   <li>On every intercepted {@code clock_gettime} call a Bernoulli trial with probability {@link
 *       #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINVAL}
 *       without invoking the real kernel call — the application sees a genuine kernel failure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code clock_gettime} returns {@code -1}; callers that omit a return-value check will
 *       silently use an uninitialised {@code timespec} — a subtle correctness bug.
 *   <li>Java's {@code System.currentTimeMillis()} and {@code System.nanoTime()} delegate to {@code
 *       clock_gettime} via JNI and may throw or return stale values depending on the JVM.
 *   <li>Distributed lease or election logic that derives timeouts from the monotonic clock may
 *       miscalculate intervals and trigger spurious leader elections or lock expiries.
 *   <li>Assert that the application emits a structured error, increments a fault metric, and does
 *       not silently propagate a zero-valued or garbage timestamp downstream.
 * </ul>
 *
 * <p>In production, {@code EINVAL} from {@code clock_gettime} most commonly indicates a seccomp
 * filter rejecting the specific clock variant, or a stripped container image in which a
 * non-standard clock identifier is absent from the kernel configuration.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX (IEEE Std 1003.1-2017, {@code clock_gettime}) mandates {@code EINVAL} when the {@code
 * clockid} argument does not refer to a known clock. The three clocks most relevant to distributed
 * Java services are {@code CLOCK_REALTIME} (wall clock, subject to NTP steps), {@code
 * CLOCK_MONOTONIC} (never steps backward, used for timeouts and heartbeats), and {@code
 * CLOCK_PROCESS_CPUTIME_ID} (requires {@code CAP_SYS_PTRACE} in hardened kernels).
 *
 * <p>The glibc wrapper for {@code clock_gettime} uses the vDSO fast path when available, bypassing
 * the kernel boundary entirely. {@code libchaos-time.so} interposes at the PLT / GOT level, which
 * intercepts the call regardless of whether the vDSO or the real syscall path is taken — making the
 * injected failure transparent to both paths.
 *
 * <p>Frameworks affected: Spring's {@code StopWatch}, Micrometer's {@code Clock}, Caffeine's expiry
 * policy, Jedis / Lettuce socket timeout computation (via {@code getsockopt SO_RCVTIMEO} which
 * reads the monotonic clock internally), Raft and Paxos implementations (etcd, Consul, Zab) that
 * use monotonic time for heartbeat intervals, and Redis's internal {@code mstime()} helper which
 * calls {@code clock_gettime(CLOCK_REALTIME)}.
 *
 * <p>Sibling annotations: {@link ChaosClockGettimeEfault} targets bad-pointer edge cases at the
 * syscall boundary; {@link ChaosClockGettimeEperm} targets capability failures for privileged clock
 * ids; {@link ChaosClockGettimeEnosys} targets kernels that do not expose the syscall at all;
 * {@link ChaosClockGettimeOffset} shifts the returned time value instead of failing the call.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeEinval(probability = 0.01)
 * class ClockGettimeEinvalTest {
 *   @Test
 *   void applicationHandlesInvalidClockId(ConnectionInfo info) {
 *     // assert that the application does not silently propagate a zero timestamp
 *     // and that a structured error is emitted to the metrics or log pipeline
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEfault
 * @see ChaosClockGettimeEperm
 * @see ChaosClockGettimeEnosys
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosClockGettimeEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.CLOCK_GETTIME, errno = TimeErrno.EINVAL)
public @interface ChaosClockGettimeEinval {

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
   * @ChaosClockGettimeEinval(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeEinval(id = "replica",  probability = 0.01)
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
    ChaosClockGettimeEinval[] value();
  }
}
