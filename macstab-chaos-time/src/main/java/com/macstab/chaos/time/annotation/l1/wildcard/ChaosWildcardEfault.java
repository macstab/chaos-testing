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
 * Injects {@code EFAULT} into every interposed time syscall ({@code clock_gettime}, {@code
 * nanosleep}, {@code usleep}), causing each to return {@code -1} with {@code errno = EFAULT} as if
 * a pointer argument pointed to inaccessible memory.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code WILDCARD}, errno = {@code
 * EFAULT}) tuple. The {@code WILDCARD} selector matches all three interposed time syscalls
 * simultaneously — equivalent to applying {@link ChaosClockGettimeEfault}, {@link
 * ChaosNanosleepEfault}, and {@link ChaosUsleepEfault} in a single annotation. No runtime
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EFAULT}
 *       without performing any real work.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>For {@code clock_gettime}, the output {@code timespec} is not written; callers must not
 *       read the struct after a non-zero return.
 *   <li>For {@code nanosleep}, both the input and remaining-time structs are not accessed; callers
 *       must not dereference the {@code rem} pointer on failure.
 *   <li>For {@code usleep}, no side-effects occur; callers must detect the error.
 *   <li>Assert that the application does not use uninitialized output buffers and that all three
 *       syscall failure paths are fully exercised by the test.
 * </ul>
 *
 * <p>In production, {@code EFAULT} across all three time syscalls simultaneously is associated with
 * severe memory corruption — stack overflow, use-after-free, or heap corruption that corrupts
 * pointer values used in the time-related call paths.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code EFAULT} wildcard is the most severe memory-error injection available in the time
 * module: it simulates a condition where the process's virtual address space has become partially
 * unmapped, which in practice is a near-crash state. Using this annotation in controlled tests
 * ensures that the error-handling paths taken in this extreme scenario perform safe cleanup (no
 * reads from unmapped output buffers, no double-free) rather than cascading into secondary
 * failures.
 *
 * <p>JVM implementations that call {@code clock_gettime} via JNI typically handle the return value
 * but may not check for {@code EFAULT} specifically; injecting it exercises any assumption in the
 * JVM's native code that the output buffer is always populated after a successful return.
 *
 * <p>Sibling per-syscall annotations ({@link ChaosClockGettimeEfault}, {@link
 * ChaosNanosleepEfault}, {@link ChaosUsleepEfault}) allow targeted injection to individual
 * syscalls.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosWildcardEfault(probability = 0.001)
 * class WildcardEfaultTest {
 *   @Test
 *   void applicationDoesNotUseUninitializedBuffersOnFaultAcrossTimeSyscalls(ConnectionInfo info) {
 *     // assert that no read from an uninitialised struct occurs
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEfault
 * @see ChaosNanosleepEfault
 * @see ChaosUsleepEfault
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosWildcardEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.WILDCARD, errno = TimeErrno.EFAULT)
public @interface ChaosWildcardEfault {

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
   * @ChaosWildcardEfault(id = "primary",  probability = 0.001)
   * @ChaosWildcardEfault(id = "replica",  probability = 0.01)
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
    ChaosWildcardEfault[] value();
  }
}
