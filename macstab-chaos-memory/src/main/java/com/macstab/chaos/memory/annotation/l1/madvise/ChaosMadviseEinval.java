/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.madvise;

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
 * Injects {@code EINVAL} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe an invalid-argument failure when providing a memory usage hint to the
 * kernel.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code EINVAL})
 * tuple. The {@code MADVISE} selector intercepts {@code madvise} calls only, leaving
 * {@code mmap}, {@code munmap}, and {@code mprotect} unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code madvise} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code madvise} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 22,
 *       {@code strerror}: "Invalid argument"; the hint is not applied.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = EINVAL} (22); the hint is not
 *       applied — the application must treat the failure as non-fatal.</li>
 *   <li>Applications that construct {@code madvise} advice values dynamically (e.g. choosing
 *       between {@code MADV_WILLNEED} and {@code MADV_RANDOM} based on runtime conditions)
 *       must handle {@code EINVAL} for unknown advice values on older kernels; assert that the
 *       application falls back to default kernel behaviour and logs the invalid advice value.</li>
 *   <li>Assert that the application does not retry with the same invalid arguments — {@code EINVAL}
 *       for {@code madvise} is deterministic; the same call will produce the same result without
 *       a code change.</li>
 * </ul>
 * Production failure mode: a kernel upgrade adds new {@code madvise} advice values (e.g.
 * {@code MADV_COLD} and {@code MADV_PAGEOUT} were added in Linux 5.4); applications deployed
 * on older kernels that attempt to use these new values receive {@code EINVAL} and silently
 * lose the memory management benefit — a cross-kernel compatibility hazard that is invisible
 * in development environments running the new kernel.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EINVAL} for {@code madvise} when the {@code advice} argument is
 * not a valid value, when {@code addr} is not page-aligned, or when {@code length} is negative
 * (on some implementations). On Linux, the kernel validates the advice value against its list
 * of supported behaviours in {@code do_madvise}; any unrecognised advice value returns
 * {@code -EINVAL}. The address alignment check returns {@code -EINVAL} (not {@code -EFAULT})
 * for misaligned {@code addr} values.
 *
 * <p>The most important production scenario for {@code EINVAL} from {@code madvise} is
 * kernel-version skew: applications compiled on a new kernel may use advice values that did
 * not exist on the production kernel. This is especially common for containerised workloads
 * where the development image runs a newer kernel than the production node. Advice values
 * added after the production kernel's release will unconditionally return {@code EINVAL}
 * in production while succeeding in development.
 *
 * <p>The JVM uses {@code madvise} for several internal operations: {@code MADV_FREE} for
 * returning heap regions to the OS (added in Linux 4.5); {@code MADV_HUGEPAGE} for promoting
 * heap regions to Huge Pages; and {@code MADV_DONTNEED} for discarding heap regions. JVM
 * versions that probe for {@code MADV_FREE} support at startup will receive {@code EINVAL}
 * on kernels prior to 4.5 and fall back to {@code MADV_DONTNEED}. This fallback is correct
 * but must be handled explicitly — assert that the JVM's fallback path is exercised under
 * this annotation.
 *
 * <p>Compared with {@code EFAULT}: {@code EINVAL} indicates the arguments are structurally
 * invalid (bad advice value, misaligned address, negative length); {@code EFAULT} indicates
 * the address range is inaccessible. Both are non-fatal for advisory hints; neither should
 * propagate to the caller as an error.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEinval(probability = 0.001)
 * class MadviseCompatibilityTest {
 *   @Test
 *   void appHandlesEinvalOnMadviseWithFallback(RedisConnectionInfo info) {
 *     // verify fallback to MADV_DONTNEED or no-hint when MADV_FREE/MADV_COLD returns EINVAL
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; madvise failures are non-fatal, so
 * any probability exercises the error path safely — test at a rate that does not flood logs.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
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
 * @ChaosMadviseEinval(probability = 0.001)
 * class MemoryFaultTest {
 *   @Test
 *   void appHandlesEinvalOnAlloc(RedisConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2 as a canary; 1.0 will block all mapped I/O
 * and crash the JVM.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class. Use the repeatable form ({@code @ChaosMadviseEinvals}) to bind different
 * probabilities to different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.EINVAL)
public @interface ChaosMadviseEinval {

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
   * @ChaosMadviseEinval(id = "primary",  probability = 0.001)
   * @ChaosMadviseEinval(id = "replica",  probability = 0.01)
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
    ChaosMadviseEinval[] value();
  }
}
