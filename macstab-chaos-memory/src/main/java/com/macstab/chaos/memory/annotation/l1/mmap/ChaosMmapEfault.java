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
 * Injects {@code EFAULT} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe a bad-address failure from any
 * memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code EFAULT}) tuple.
 * The {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use
 * {@code ChaosMmapAnonEfault} or {@code ChaosMmapFileEfault} for narrower fault isolation.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EFAULT} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 14,
 *       {@code strerror}: "Bad address".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EFAULT} (14); any code that
 *       dereferences the returned value will receive {@code SIGSEGV}.</li>
 *   <li>glibc {@code malloc} propagates {@code NULL}; JVM allocators raise {@code OutOfMemoryError};
 *       file-mapping code that does not check the return value will crash.</li>
 *   <li>Assert that no code path dereferences {@code MAP_FAILED} — verify the application
 *       produces a diagnostic rather than a segmentation fault.</li>
 * </ul>
 * Production failure mode: JIT runtimes that pass stale or computed hint addresses to {@code mmap}
 * can receive {@code EFAULT} from a real kernel when the hint falls outside accessible address
 * space — a latent bug that is nearly impossible to reproduce without injection.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EFAULT} for {@code mmap} when the {@code addr} hint references
 * addresses outside the accessible process address space. The kernel validates the hint in
 * {@code do_mmap_pgoff} and returns {@code -EFAULT} if it falls in kernel space or in an
 * inaccessible region. For anonymous mappings with a {@code NULL} hint, {@code EFAULT} never
 * occurs naturally; for file-backed mappings with a non-null hint, it is possible when the
 * caller provides a garbage hint pointer from a stale cache.
 *
 * <p>The broad {@code MMAP} selector simultaneously affects anonymous allocations (heap path)
 * and file-backed mappings (I/O path). File-mapping code that caches the result of a previous
 * successful mapping and reuses it as a hint for subsequent mappings is particularly at risk:
 * if the mapping was unmapped between calls, the hint becomes stale and the kernel returns
 * {@code EFAULT}.
 *
 * <p>The JVM uses {@code mmap} with explicit hint addresses for its internal allocators
 * (G1 region placement, code cache placement); these use carefully computed addresses that
 * should never produce {@code EFAULT} in practice. This annotation exercises the error-handling
 * code in the JVM's native allocator wrappers that has likely never been triggered in any
 * production deployment.
 *
 * <p>At high probability values (approaching 1.0), {@code EFAULT} injection will cause the
 * dynamic linker itself to fail when loading shared libraries, producing unmaskable crashes.
 * Keep probability very low (1e-5 to 1e-4) for this annotation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEfault(probability = 0.0001)
 * class AddressCorruptionTest {
 *   @Test
 *   void appHandlesEfaultWithoutCrash(RedisConnectionInfo info) {
 *     // verify the application produces a diagnostic rather than SIGSEGV
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> very low rates (1e-5 to 1e-4); {@code EFAULT} at
 * high probability causes unmaskable process crashes.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EFAULT)
public @interface ChaosMmapEfault {

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
   * @ChaosMmapEfault(id = "primary",  probability = 0.001)
   * @ChaosMmapEfault(id = "replica",  probability = 0.01)
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
    ChaosMmapEfault[] value();
  }
}
