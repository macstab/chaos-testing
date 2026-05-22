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
 * Lets the first {@link #successesBeforeFailure} libchaos-intercepted {@code pthread_create} calls
 * succeed, then injects {@code EAGAIN} on every subsequent call until the rule is removed.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive encoding exactly one (selector
 * = {@code PTHREAD_CREATE}, errno = {@code EAGAIN}, effect = FAIL_AFTER) tuple. FAIL_AFTER is the
 * process module's counter-gated effect — distinct from ERRNO (probabilistic) and LATENCY
 * (unconditional). It models resource-exhaustion scenarios where the first N operations succeed and
 * then the system runs out of capacity.
 *
 * <p><strong>What chaos this applies:</strong> the libchaos-process interceptor counts successful
 * {@code pthread_create} calls. After {@link #successesBeforeFailure} successes the counter trips
 * and every subsequent call returns {@code -1} with {@code errno = EAGAIN}, regardless of real
 * kernel capacity. The counter resets every time the rule is re-applied (e.g. across test methods
 * if the annotation is at class scope).
 *
 * <p><strong>How this occurs (mechanism):</strong> the
 * {@code @SyscallLevelChaos(LibchaosLib.PROCESS)} annotation causes {@code ChaosTestingExtension}
 * to upload {@code libchaos-process.so} and prepend it to {@code LD_PRELOAD}. The shared library
 * interposes the libc wrappers for the process-management syscall family. This annotation installs
 * a FAIL_AFTER rule via {@code AdvancedProcessChaos.apply(container, rule)}.
 *
 * <p><strong>What is required:</strong>
 *
 * <ul>
 *   <li><strong>Linux host</strong> — {@code LD_PRELOAD} does not apply on macOS or Windows.
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.PROCESS)}</strong> on the container
 *       annotation — omitting it causes an {@code ExtensionConfigurationException} at {@code
 *       beforeAll}.
 *   <li><strong>glibc-based container image</strong> — musl-based images may not honour {@code
 *       LD_PRELOAD} for statically-linked processes.
 *   <li><strong>{@code macstab-chaos-process} on the test classpath.</strong>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEagainFailAfter(successesBeforeFailure = 128)
 * class ProcessExhaustionTest {
 *   @Test
 *   void handlesExhaustion(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Guidance:</strong> set {@link #successesBeforeFailure} to the number of {@code
 * pthread_create} calls the application is expected to make before hitting the limit. Typically
 * 5–200 for container-scoped tests. Zero means the very first call fails.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds to a single container; the default empty string
 * applies to every capable container. Use the repeatable form
 * ({@code @ChaosPthreadCreateEagainFailAfter.Repeatable}) to apply different counters to different
 * containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosPthreadCreateEagainFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EAGAIN)
public @interface ChaosPthreadCreateEagainFailAfter {

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
   * @ChaosPthreadCreateEagainFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPthreadCreateEagainFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPthreadCreateEagainFailAfter[] value();
  }
}
