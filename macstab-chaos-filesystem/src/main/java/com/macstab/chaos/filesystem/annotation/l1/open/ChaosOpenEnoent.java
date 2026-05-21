/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.open;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code ENOENT} on every libchaos-intercepted {@code open} call inside
 * the target container, making the call fail as if the kernel returned {@code ENOENT}.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive — the smallest declarative
 * chaos unit. It encodes exactly one (selector, errno = {@code ENOENT}) pair and has no
 * runtime selector-errno matrix to validate. The combination is safe by construction: this
 * annotation class exists only because {@code ENOENT} is a valid POSIX result of
 * {@code open}.
 *
 * <p><strong>What chaos this applies:</strong> on every {@code open} call that the
 * libchaos interceptor sees, a Bernoulli trial with probability {@link #probability} is run.
 * When it fires the interceptor returns {@code -1} and sets {@code errno = ENOENT} — from
 * the application's perspective this is indistinguishable from a real kernel-level failure.
 * Specifically this simulates: no such file or directory — binary path not found or file deleted.
 *
 * <p><strong>How this occurs (mechanism):</strong> the {@code @SyscallLevelChaos(LibchaosLib.IO)} annotation causes {@code ChaosTestingExtension} to upload {@code libchaos-io.so} and prepend it to {@code LD_PRELOAD}. The shared library interposes the filesystem libc wrappers (open, read, write, close, fsync, etc.) at the dynamic-linker level. This annotation installs a rule via {@code AdvancedFilesystemChaos.apply(container, rule)}.
 *
 * <p><strong>What is required:</strong>
 * <ul>
 *   <li><strong>Linux host</strong> — libchaos uses {@code LD_PRELOAD}, which does not apply
 *       on macOS or Windows; annotate the test with {@code @DisabledOnOs(OS.WINDOWS)}.</li>
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.IO)}</strong> on the container annotation
 *       (e.g. {@code @AppContainer}) — omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.</li>
 *   <li><strong>glibc-based container image</strong> — musl-based images (Alpine default) may not
 *       honour {@code LD_PRELOAD} for statically-linked processes; use Debian-slim instead.</li>
 *   <li><strong>{@code macstab-chaos-filesystem} on the test classpath</strong> — without it the translator
 *       class cannot be loaded and the extension throws {@code ClassNotFoundException}.</li>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenEnoent(probability = 0.001)
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
 * class. Use the repeatable form ({@code @ChaosOpenEnoents}) to bind different probabilities to
 * different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosOpenEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.OPEN, errno = Errno.ENOENT)
public @interface ChaosOpenEnoent {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-io
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   * <pre>{@code
   * @ChaosOpenEnoent(id = "primary",  probability = 0.001)
   * @ChaosOpenEnoent(id = "replica",  probability = 0.01)
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
    ChaosOpenEnoent[] value();
  }
}
