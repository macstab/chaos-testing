/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.pthread_create;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Injects {@code EINVAL} into {@code pthread_create} calls intercepted by libchaos-process, causing
 * the calling code to observe an invalid-argument failure when attempting to create a new thread
 * with an invalid {@code pthread_attr_t}.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EINVAL})
 * tuple. The {@code PTHREAD_CREATE} selector intercepts {@code pthread_create} calls only, leaving
 * {@code fork}, {@code posix_spawn}, and all other process syscalls unaffected. Compile-time safety:
 * invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code pthread_create} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code pthread_create} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code EINVAL} directly (pthread_create
 *       returns the error code, not -1; it does not set errno).</li>
 *   <li>The calling code receives: return value {@code EINVAL} (22),
 *       {@code strerror(EINVAL)}: "Invalid argument"; the {@code pthread_attr_t} attribute object
 *       contains an invalid setting; no thread is created.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code pthread_create} returns {@code EINVAL}; no thread is created; assert that the
 *       application treats EINVAL as a non-retryable programming error — the thread attribute
 *       structure contains an invalid value that must be fixed in code, not retried.</li>
 *   <li>Applications that build {@code pthread_attr_t} structures dynamically (from configuration
 *       or serialised state) must validate the attribute before calling pthread_create — assert
 *       that EINVAL from pthread_create triggers a diagnostic that includes the stack size,
 *       scheduling policy, guard size, and detach state for operator debugging.</li>
 *   <li>Assert that the application does not retry on EINVAL; do not apply exponential backoff to
 *       a programming error that will reproduce identically on every attempt with the same
 *       attribute structure.</li>
 * </ul>
 * Production failure mode: a thread pool uses a serialisation framework to persist and restore its
 * configuration including {@code pthread_attr_t} parameters; a version upgrade changes the encoding
 * of a scheduling policy field, producing an attribute structure that NPTL rejects with EINVAL;
 * the pool does not log the attribute values on failure, making root cause analysis require
 * reproducing the exact serialised configuration.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EINVAL} from {@code pthread_create} is returned when the {@code attr} argument
 * contains an invalid value. Sources include: stack size smaller than {@code PTHREAD_STACK_MIN}
 * (typically 16384 bytes); stack size not a multiple of the system page size in some
 * implementations; invalid scheduling policy (not SCHED_OTHER, SCHED_FIFO, or SCHED_RR); scheduling
 * priority out of range for the policy; invalid guard size; detach state not PTHREAD_CREATE_JOINABLE
 * or PTHREAD_CREATE_DETACHED. If {@code attr} is {@code NULL}, pthread_create uses default
 * attributes and EINVAL cannot be returned from attribute validation.
 *
 * <p>pthread_create follows the POSIX error-return convention for thread functions: it returns the
 * error code directly (not -1) and does not set {@code errno}. Code that checks
 * {@code if (ret == -1)} or {@code if (errno == EINVAL)} after pthread_create silently misses
 * EINVAL (22). Code that tests {@code if (ret != 0)} is correct. EINVAL (22) and EAGAIN (11)
 * require different responses: EINVAL is a non-retryable attribute configuration error; EAGAIN is
 * a transient resource shortage that warrants retry.
 *
 * <p>A common source of EINVAL in containerised applications is custom thread attribute builders
 * that compute stack size from a configured value without clamping to PTHREAD_STACK_MIN. A
 * configuration change that reduces the requested stack size below the minimum produces a
 * permanent EINVAL on every subsequent thread creation, which causes thread pools to fail to
 * expand under load at exactly the moment when expansion is most needed.
 *
 * <p>EINVAL from pthread_create also fires when the scheduling policy or priority is incompatible
 * with the process's privilege level — for example, requesting SCHED_FIFO in a thread attribute
 * without CAP_SYS_NICE. In this case EINVAL may be returned instead of EPERM depending on the
 * kernel version and policy implementation. Applications should check both EINVAL and EPERM when
 * validating scheduling attribute errors.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEinval(probability = 0.01)
 * class PthreadCreateAttributeValidationTest {
 *   @Test
 *   void threadPoolLogsAttributeValuesOnEinvalAndDoesNotRetry(ConnectionInfo info) {
 *     // verify return value checked (not errno); EINVAL treated as non-retryable;
 *     // stack size and scheduling policy logged; alert raised; no retry loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EINVAL is a configuration error;
 * any non-zero probability exercises the non-retryable error diagnostic path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPthreadCreateEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EINVAL)
public @interface ChaosPthreadCreateEinval {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

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
   * @ChaosPthreadCreateEinval(id = "primary",  probability = 0.001)
   * @ChaosPthreadCreateEinval(id = "replica",  probability = 0.01)
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
    ChaosPthreadCreateEinval[] value();
  }
}
