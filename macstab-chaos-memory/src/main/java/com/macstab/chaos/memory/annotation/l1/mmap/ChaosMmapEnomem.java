/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * Injects {@code ENOMEM} on every libchaos-memory-intercepted {@code mmap} call inside the target
 * container, making the call fail as if the kernel returned {@code ENOMEM}.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive — the smallest declarative
 * chaos unit. It encodes exactly one (selector = {@code MMAP}, errno = {@code ENOMEM}) pair. The
 * combination is safe by construction: this annotation class exists only because {@code ENOMEM} is
 * a valid POSIX result of {@code mmap}; the invalid combinations simply have no annotation class,
 * so the selector × errno matrix cannot be violated at compile time.
 *
 * <p><strong>What chaos this applies:</strong> on every {@code mmap} call that the libchaos-memory
 * interceptor sees, a Bernoulli trial with probability {@link #probability} is run. When it fires
 * the interceptor returns {@code -1} and sets {@code errno = ENOMEM} before the kernel call
 * completes — from the application perspective this is indistinguishable from a real kernel-level
 * failure. Specifically this simulates: out of memory — the canonical libc
 * malloc()-allocation-failure code for sizes >= MMAP_THRESHOLD.
 *
 * <p><strong>How this occurs (mechanism):</strong> the
 * {@code @SyscallLevelChaos(LibchaosLib.MEMORY)} annotation on the container declaration causes
 * {@code ChaosTestingExtension} to upload {@code libchaos-memory.so} into the container and prepend
 * it to {@code LD_PRELOAD} before the container process starts. The shared library interposes the
 * libc wrappers for {@code mmap}, {@code munmap}, {@code mprotect}, and {@code madvise} at the
 * dynamic-linker level. This annotation then installs a rule via {@code
 * AdvancedMemoryChaos.apply(container, rule)} that configures the interposer with the selector and
 * probability you specify.
 *
 * <p><strong>What is required:</strong>
 *
 * <ul>
 *   <li><strong>Linux host</strong> — libchaos uses {@code LD_PRELOAD} which does not apply on
 *       macOS or Windows containers; annotate the test class with {@code @DisabledOnOs(OS.WINDOWS)}
 *       and be aware of macOS Docker limitations.
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.MEMORY)}</strong> on the container annotation
 *       (e.g. {@code @RedisStandalone}) — this installs the shared library before container start;
 *       omitting it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>glibc-based container image</strong> — musl-based images (Alpine default) do not
 *       honour {@code LD_PRELOAD} for statically-linked binaries; use a glibc variant or the
 *       Debian-slim image instead.
 *   <li><strong>{@code macstab-chaos-memory} on the test classpath</strong> — without it the
 *       translator class cannot be loaded and {@code ChaosTestingExtension} throws {@code
 *       ClassNotFoundException} wrapped in {@code ExtensionConfigurationException}.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEnomem(probability = 0.001)
 * class MemoryFaultTest {
 *   @Test
 *   void appHandlesEnomemOnAlloc(RedisConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 mirrors realistic OOM rates; 1.0 prevents
 * the container from starting.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class. Use the repeatable form ({@code @ChaosMmapEnomems}) to bind different
 * probabilities to different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.ENOMEM)
public @interface ChaosMmapEnomem {

  /**
   * Probability that the fault fires when the rule matches, in the range {@code (0.0, 1.0]}. A
   * value of {@code 1.0} makes every matching call fail; {@code 0.001} fails one call in a
   * thousand. Values outside the range {@code (0.0, 1.0]} are rejected at rule construction time.
   */
  double probability() default 1.0;

  /**
   * Container id to bind this rule to. The value must match the {@code id} attribute of a container
   * annotation (e.g. {@code @RedisStandalone(id = "primary")}) on the same test class. The default
   * empty string {@code ""} applies the rule to every memory-chaos-capable container in the test
   * class. A non-empty id that does not match any declared container causes an {@code
   * ExtensionConfigurationException} at {@code beforeAll}.
   */
  String id() default "";

  /**
   * Policy applied when the active backend cannot honour the libchaos-memory requirement. {@link
   * OnMissingEnv#ERROR} (the default) fails the test class with an {@code
   * ExtensionConfigurationException} at {@code beforeAll}. {@link OnMissingEnv#ABORT} raises a
   * {@code TestAbortedException} instead, which most CI systems report as YELLOW (skipped/aborted)
   * rather than RED (failed), keeping the build clean in environments where libchaos is
   * unavailable.
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMmapEnomem(id = "primary",  probability = 0.001)
   * @ChaosMmapEnomem(id = "replica",  probability = 0.01)
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
    ChaosMmapEnomem[] value();
  }
}
