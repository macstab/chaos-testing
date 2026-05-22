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
 * Injects {@code EINVAL} on every libchaos-intercepted {@code clock gettime} call inside the target
 * container, making the call fail as if the kernel returned {@code EINVAL}.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive — the smallest declarative
 * chaos unit. It encodes exactly one (selector, errno = {@code EINVAL}) pair and has no runtime
 * selector-errno matrix to validate. The combination is safe by construction: this annotation class
 * exists only because {@code EINVAL} is a valid POSIX result of {@code clock gettime}.
 *
 * <p><strong>What chaos this applies:</strong> on every {@code clock gettime} call that the
 * libchaos interceptor sees, a Bernoulli trial with probability {@link #probability} is run. When
 * it fires the interceptor returns {@code -1} and sets {@code errno = EINVAL} — from the
 * application's perspective this is indistinguishable from a real kernel-level failure.
 * Specifically this simulates: invalid argument — bad length, flags, or option; the universal
 * canary errno.
 *
 * <p><strong>How this occurs (mechanism):</strong> the {@code @SyscallLevelChaos(LibchaosLib.TIME)}
 * annotation causes {@code ChaosTestingExtension} to upload {@code libchaos-time.so} and prepend it
 * to {@code LD_PRELOAD}. The shared library interposes the libc wrappers for {@code clock_gettime},
 * {@code nanosleep}, and {@code usleep}. This annotation installs a rule via {@code
 * AdvancedTimeChaos.apply(container, rule)}.
 *
 * <p><strong>What is required:</strong>
 *
 * <ul>
 *   <li><strong>Linux host</strong> — libchaos uses {@code LD_PRELOAD}, which does not apply on
 *       macOS or Windows; annotate the test with {@code @DisabledOnOs(OS.WINDOWS)}.
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.TIME)}</strong> on the container annotation
 *       (e.g. {@code @RedisStandalone}) — omitting it causes an {@code
 *       ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>glibc-based container image</strong> — musl-based images (Alpine default) may not
 *       honour {@code LD_PRELOAD} for statically-linked processes; use Debian-slim instead.
 *   <li><strong>{@code macstab-chaos-time} on the test classpath</strong> — without it the
 *       translator class cannot be loaded and the extension throws {@code ClassNotFoundException}.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeEinval(probability = 0.001)
 * class FaultTest {
 *   @Test
 *   void appHandlesFailure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> use low rates (1e-4 to 1e-2) to avoid breaking
 * container initialisation.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every capable container in the test
 * class. Use the repeatable form ({@code @ChaosClockGettimeEinvals}) to bind different
 * probabilities to different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
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
