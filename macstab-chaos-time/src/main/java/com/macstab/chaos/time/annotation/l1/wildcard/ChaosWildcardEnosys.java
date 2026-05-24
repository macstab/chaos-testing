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
 * Injects {@code ENOSYS} into every interposed time syscall ({@code clock_gettime}, {@code
 * nanosleep}, {@code usleep}), causing each to return {@code -1} with {@code errno = ENOSYS} as if
 * the kernel does not implement the syscall variant requested.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code WILDCARD}, errno = {@code
 * ENOSYS}) tuple. The {@code WILDCARD} selector matches all three interposed time syscalls
 * simultaneously — equivalent to applying {@link ChaosClockGettimeEnosys}, {@link
 * ChaosNanosleepEnosys}, and {@link ChaosUsleepEnosys} in a single annotation. No runtime
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = ENOSYS}
 *       without performing any real work.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All three time syscalls simultaneously report "function not implemented"; the application
 *       must detect that no time interface is available and degrade or fail cleanly.
 *   <li>glibc wrappers that provide {@code usleep(3)} via {@code nanosleep(2)} will encounter
 *       {@code ENOSYS} on both the wrapper and the underlying syscall, closing the fall-through
 *       path that some applications rely on.
 *   <li>Assert that the application selects a non-syscall fallback (e.g. monotonic counter, {@code
 *       CLOCK_COARSE}) or terminates with a meaningful diagnostic rather than crashing silently.
 * </ul>
 *
 * <p>In production, simultaneous {@code ENOSYS} across all time syscalls occurs on stripped or
 * embedded kernels that omit the {@code clock_gettime} VDSO and the {@code nanosleep} syscall — a
 * configuration sometimes used in ultra-minimal container base images.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>On a standard Linux kernel, {@code clock_gettime} is universally available via the vDSO and
 * never returns {@code ENOSYS} in normal operation. However, minimal kernel configurations
 * (CONFIG_POSIX_TIMERS=n) can disable the POSIX timer subsystem entirely, causing all {@code
 * clock_gettime} variants except {@code CLOCK_REALTIME} to return {@code ENOSYS}. The wildcard
 * injection simulates this condition across all three time interfaces simultaneously.
 *
 * <p>The JVM's time subsystem ({@code System.nanoTime()}, {@code System.currentTimeMillis()}) calls
 * {@code clock_gettime} via JNI on Linux. If the JVM receives {@code ENOSYS} it typically falls
 * back to {@code gettimeofday(2)}, which is not interposed by {@code libchaos-time.so}. Application
 * code that calls time interfaces directly through JNI or via a native library will see the {@code
 * ENOSYS} errors; pure Java code that uses {@code System.nanoTime()} may not, depending on the JVM
 * fallback path.
 *
 * <p>This annotation is most useful for testing native clients (libpq, librdkafka, libcurl) that
 * call POSIX time interfaces directly. Injecting {@code ENOSYS} across all three simultaneously
 * verifies that the native library's fallback chain reaches a stable end state rather than looping
 * through alternatives indefinitely.
 *
 * <p>Sibling per-syscall annotations ({@link ChaosClockGettimeEnosys}, {@link
 * ChaosNanosleepEnosys}, {@link ChaosUsleepEnosys}) allow targeted injection to a single syscall
 * when the fall-through chain of a specific library needs to be tested in isolation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosWildcardEnosys(probability = 0.005)
 * class WildcardEnosysTest {
 *   @Test
 *   void applicationSelectsFallbackWhenAllTimeSyscallsMissing(ConnectionInfo info) {
 *     // assert that the application reaches a stable fallback and does not crash
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEnosys
 * @see ChaosNanosleepEnosys
 * @see ChaosUsleepEnosys
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosWildcardEnosys.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.WILDCARD, errno = TimeErrno.ENOSYS)
public @interface ChaosWildcardEnosys {

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
   * @ChaosWildcardEnosys(id = "primary",  probability = 0.001)
   * @ChaosWildcardEnosys(id = "replica",  probability = 0.01)
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
    ChaosWildcardEnosys[] value();
  }
}
