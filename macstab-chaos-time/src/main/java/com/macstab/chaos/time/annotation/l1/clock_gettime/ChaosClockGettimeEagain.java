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
 * Injects {@code EAGAIN} into {@code clock_gettime(2)}, causing the call to return {@code -1} with
 * {@code errno = EAGAIN} as if the kernel temporarily could not service the request.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, errno = {@code
 * EAGAIN}) tuple. The tuple is safe by construction — {@code EAGAIN} is a valid transient POSIX
 * result indicating resource temporarily unavailable. No runtime selector-errno validation is
 * needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EAGAIN}
 *       without invoking the real kernel call — the application sees a transient kernel failure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code clock_gettime} returns {@code -1}; well-written callers retry immediately; callers
 *       without a retry loop permanently lose the timestamp sample.
 *   <li>High rates will continuously exhaust retry budgets in timer-sensitive hot paths (event
 *       loops, scheduled tasks, connection pool eviction).
 *   <li>Metrics pipelines that record wall-clock timestamps on every sample will emit gaps or
 *       double-count intervals if they suppress the error silently.
 *   <li>Assert that the application implements bounded retry with backoff and emits a metric
 *       counter for transient clock failures.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from {@code clock_gettime} is an extremely unusual signal
 * primarily seen in severely resource-constrained kernels or exotic POSIX emulation layers; it is
 * more common in {@code nanosleep} and {@code usleep} under signal pressure.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Standard Linux kernels do not return {@code EAGAIN} from {@code clock_gettime} under normal
 * conditions; the call is synchronous and does not queue work. Injecting it via {@code
 * libchaos-time.so} therefore exercises defensive code paths that application developers write to
 * handle any possible {@code -1} return — regardless of whether the specific errno is expected in
 * this context.
 *
 * <p>This is particularly valuable for verifying that generated gRPC stubs, ORM layers, and
 * framework internals do not hard-code the assumption that {@code clock_gettime} always succeeds. A
 * surprising number of production bugs occur when an unconditionally assumed syscall fails for the
 * first time on a degraded host.
 *
 * <p>Sibling annotations: {@link ChaosClockGettimeEinval} targets invalid clock ids; {@link
 * ChaosClockGettimeEintr} (via wildcard) targets signal interruption; {@link
 * ChaosClockGettimeLatency} injects delay without error, surfacing timeout violations.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeEagain(probability = 1e-3)
 * class ClockGettimeEagainTest {
 *   @Test
 *   void applicationRetriesTransientClockFailure(ConnectionInfo info) {
 *     // assert that the transient failure is handled and the timestamp pipeline continues
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEinval
 * @see ChaosClockGettimeLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosClockGettimeEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.CLOCK_GETTIME, errno = TimeErrno.EAGAIN)
public @interface ChaosClockGettimeEagain {

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
   * @ChaosClockGettimeEagain(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeEagain(id = "replica",  probability = 0.01)
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
    ChaosClockGettimeEagain[] value();
  }
}
