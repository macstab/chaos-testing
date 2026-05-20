/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

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
 * Injects {@code ERRNO} on every libchaos-intercepted {@code execve} call inside
 * the target container, making the call fail as if the kernel returned {@code ERRNO}.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive — the smallest declarative
 * chaos unit. It encodes exactly one (selector, errno = {@code ERRNO}) pair and has no
 * runtime selector-errno matrix to validate. The combination is safe by construction: this
 * annotation class exists only because {@code ERRNO} is a valid POSIX result of
 * {@code execve}.
 *
 * <p><strong>What chaos this applies:</strong> on every {@code execve} call that the
 * libchaos interceptor sees, a Bernoulli trial with probability {@link #probability} is run.
 * When it fires the interceptor returns {@code -1} and sets {@code errno = ERRNO} — from
 * the application's perspective this is indistinguishable from a real kernel-level failure.
 * Specifically this simulates: a POSIX error condition.
 *
 * <p><strong>How this occurs (mechanism):</strong> the {@code @SyscallLevelChaos(LibchaosLib.PROCESS)} annotation causes {@code ChaosTestingExtension} to upload {@code libchaos-process.so} and prepend it to {@code LD_PRELOAD}. The shared library interposes the libc wrappers for the process-management syscall family at the dynamic-linker level. This annotation installs a rule via {@code AdvancedProcessChaos.apply(container, rule)}.
 *
 * <p><strong>What is required:</strong>
 * <ul>
 *   <li><strong>Linux host</strong> — libchaos uses {@code LD_PRELOAD}, which does not apply
 *       on macOS or Windows; annotate the test with {@code @DisabledOnOs(OS.WINDOWS)}.</li>
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.PROCESS)}</strong> on the container annotation
 *       (e.g. {@code @AppContainer}) — omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.</li>
 *   <li><strong>glibc-based container image</strong> — musl-based images (Alpine default) may not
 *       honour {@code LD_PRELOAD} for statically-linked processes; use Debian-slim instead.</li>
 *   <li><strong>{@code macstab-chaos-process} on the test classpath</strong> — without it the translator
 *       class cannot be loaded and the extension throws {@code ClassNotFoundException}.</li>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEnoentFailAfter(probability = 0.001)
 * class FaultTest {
 *   @Test
 *   void appHandlesFailure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> use low rates (1e-4 to 1e-2) to avoid breaking container initialisation.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every capable container in the test
 * class. Use the repeatable form ({@code @ChaosExecveEnoentFailAfters}) to bind different probabilities to
 * different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosExecveEnoentFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.ENOENT)
public @interface ChaosExecveEnoentFailAfter {

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
   * <pre>{@code
   * @ChaosExecveEnoentFailAfter(id = "primary",  probability = 0.001)
   * @ChaosExecveEnoentFailAfter(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD
  })
  @interface Repeatable {
    ChaosExecveEnoentFailAfter[] value();
  }
}
