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
 * Injects {@code EFAULT} into {@code clock_gettime(2)}, causing the call to return {@code -1} with
 * {@code errno = EFAULT} as if the kernel found the {@code timespec} output pointer invalid.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, errno =
 * {@code EFAULT}) tuple. The tuple is safe by construction — {@code EFAULT} is a documented POSIX
 * result of {@code clock_gettime(2)} when the {@code tp} pointer passed by the caller points
 * outside the accessible address space. No runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code clock_gettime} call a Bernoulli trial with probability
 *       {@link #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EFAULT}
 *       without invoking the real kernel call — the application sees a genuine kernel failure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code clock_gettime} returns {@code -1}; the {@code timespec} struct is not written,
 *       so any subsequent read of its fields produces undefined results.
 *   <li>Callers that propagate the uninitialized struct to timeout computations risk negative or
 *       astronomically large sleep durations, triggering watchdog trips or busy-loops.
 *   <li>Java's {@code System.nanoTime()} may return a stale cached value or throw from native
 *       code depending on how the JVM handles the failed JNI call.
 *   <li>Assert that the application logs the failure, does not hang, and does not produce a
 *       wall-clock delta that exceeds a sanity bound.
 * </ul>
 *
 * <p>In production, {@code EFAULT} from {@code clock_gettime} is rare but appears in memory-
 * corrupted processes, stack-overflow scenarios where the {@code timespec} buffer is on a
 * guard page, or containers with unusual memory layout from custom allocators.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX (IEEE Std 1003.1-2017, {@code clock_gettime}) mandates {@code EFAULT} when the {@code tp}
 * argument points to an inaccessible memory location. In the kernel this is detected by the
 * {@code put_user()} / {@code copy_to_user()} path that writes the result back to userspace; if the
 * destination is unmapped or read-only, the kernel faults internally and returns {@code EFAULT} to
 * the caller rather than propagating a SIGSEGV.
 *
 * <p>{@code libchaos-time.so} injects the error before the kernel boundary is reached, so the
 * injected failure is truly indistinguishable from the hardware-triggered variant. The vDSO fast
 * path is also covered because the interposer wraps the PLT entry, not the raw kernel interface.
 *
 * <p>This errno is adjacent to memory-safety bugs: a real {@code EFAULT} during {@code clock_gettime}
 * almost always indicates a corrupted stack or heap. Using it as a chaos target reveals whether
 * error-handling paths perform safe cleanup (not reading the struct) rather than cascading into
 * further undefined behavior. Frameworks such as Netty's {@code HashedWheelTimer} and
 * {@code java.util.concurrent.ScheduledThreadPoolExecutor} read the monotonic clock frequently;
 * a mishandled {@code EFAULT} here can stall thread pools.
 *
 * <p>Sibling annotations: {@link ChaosClockGettimeEinval} targets unknown clock ids;
 * {@link ChaosClockGettimeEperm} targets capability failures; {@link ChaosClockGettimeOffset}
 * skews the returned timestamp instead of failing the call outright.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeEfault(probability = 0.001)
 * class ClockGettimeEfaultTest {
 *   @Test
 *   void applicationDoesNotReadUninitializedTimespec(ConnectionInfo info) {
 *     // assert that the application logs the fault and does not use the struct
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEinval
 * @see ChaosClockGettimeEperm
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosClockGettimeEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.CLOCK_GETTIME, errno = TimeErrno.EFAULT)
public @interface ChaosClockGettimeEfault {

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
   * @ChaosClockGettimeEfault(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeEfault(id = "replica",  probability = 0.01)
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
    ChaosClockGettimeEfault[] value();
  }
}
