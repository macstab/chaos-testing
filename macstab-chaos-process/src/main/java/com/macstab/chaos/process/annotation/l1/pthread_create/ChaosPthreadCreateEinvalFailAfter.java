/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.pthread_create;

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
 * After {@link #successesBeforeFailure} successful {@code pthread_create} calls, injects {@code
 * EINVAL} on every subsequent call, modelling a thread attribute configuration regression that
 * makes all subsequent thread creation attempts invalid.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EINVAL},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code pthread_create} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code pthread_create}
 *       call returns {@code EINVAL} directly (pthread_create returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EINVAL} (22); no thread is created; the
 *       {@code pthread_attr_t} attribute contains an invalid value.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EINVAL}; assert that the application treats EINVAL as a non-retryable
 *       programming error — the attribute structure must be fixed in code, not retried — and logs
 *       the attribute values (stack size, scheduling policy, guard size) for operator debugging.
 *   <li>FAIL_AFTER models the attribute configuration regression: N threads are created with a
 *       valid attribute; a hot-reload or dynamic reconfiguration produces an invalid {@code
 *       pthread_attr_t}; all subsequent creates fail with EINVAL — assert that the application
 *       detects this transition and escalates with the invalid attribute values.
 *   <li>Assert that the application does not apply retry backoff to EINVAL; retry loops on EINVAL
 *       are semantically wrong and will repeat indefinitely with the same invalid attribute.
 * </ul>
 *
 * Production failure mode: a thread pool uses a configuration management system to update its
 * thread attribute at runtime; a hot-reload applies a stack-size setting that is below
 * PTHREAD_STACK_MIN; after N threads are created with the old (valid) attribute, all subsequent
 * creates return EINVAL; the pool does not detect the configuration regression and surfaces a
 * generic "thread creation failed" without logging the invalid stack size.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the attribute configuration regression: N creates succeed with a valid
 * attribute; after a configuration change the attribute becomes invalid; all subsequent creates
 * fail with EINVAL. Real EINVAL from pthread_create is not probabilistic — it fires
 * deterministically whenever an invalid attribute is used and persists until the attribute is
 * corrected. pthread_create returns the error code directly — checking {@code if (ret == -1)}
 * silently misses EINVAL (22).
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (N creates with the
 * valid attribute, simulating normal operation before the hot-reload); subsequent test methods
 * exercise the EINVAL path without requiring a container restart. Set {@link
 * #successesBeforeFailure} to the number of thread creations expected to occur between
 * configuration hot-reloads.
 *
 * <p>EINVAL from pthread_create after a hot-reload is a common source of thread pool silent
 * failures in production: the configuration framework validates the stack size as a positive
 * integer but does not enforce the PTHREAD_STACK_MIN constraint; a configuration change to a "small
 * stack for efficiency" produces EINVAL on every subsequent create. The FAIL_AFTER effect forces
 * this exact sequence in a repeatable test.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEinvalFailAfter(successesBeforeFailure = 10)
 * class PthreadCreateAttributeRegressionTest {
 *   @Test
 *   void threadPoolLogsStackSizeOnEinvalAfterHotReloadAndDoesNotRetry(ConnectionInfo info) {
 *     // first 10 creates succeed; subsequent creates return EINVAL;
 *     // verify stack size logged; scheduling policy logged; no retry; alert escalated;
 *     // return value checked (not errno)
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * thread creates expected before the hot-reload applies the invalid attribute; values 5–50 cover
 * most pool configuration scenarios; 0 means every create is invalid from startup.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPthreadCreateEinvalFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EINVAL)
public @interface ChaosPthreadCreateEinvalFailAfter {

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
   * @ChaosPthreadCreateEinvalFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPthreadCreateEinvalFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPthreadCreateEinvalFailAfter[] value();
  }
}
