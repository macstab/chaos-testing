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
 * Injects {@code ENOMEM} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe a memory-exhaustion failure when providing a memory usage hint to the
 * kernel.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code ENOMEM}) tuple.
 * The {@code MADVISE} selector intercepts {@code madvise} calls only, leaving {@code mmap}, {@code
 * munmap}, and {@code mprotect} unaffected. Compile-time safety: invalid selector/errno
 * combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code madvise} wrapper at the dynamic-linker level.
 *   <li>On each {@code madvise} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12, {@code strerror}: "Out of
 *       memory (cannot allocate memory)"; the hint is not applied.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = ENOMEM} (12); the hint is not applied —
 *       the kernel will manage the region with default policies.
 *   <li>Applications that use {@code madvise(MADV_WILLNEED)} to pre-load data before a
 *       latency-sensitive operation must handle {@code ENOMEM} gracefully — assert that the
 *       operation proceeds without the pre-fault benefit and surfaces a degraded-performance
 *       warning rather than an error.
 *   <li>Assert that the application does not treat {@code ENOMEM} from {@code madvise} as fatal —
 *       it is an advisory hint failure, not a resource allocation failure that prevents the
 *       application from making progress.
 * </ul>
 *
 * Production failure mode: under severe memory pressure, the kernel cannot allocate the page table
 * entries needed to service a {@code MADV_WILLNEED} pre-fault request; it returns {@code ENOMEM}
 * from {@code madvise} rather than failing silently, but the application's subsequent access to the
 * region triggers on-demand paging with cache misses, causing latency spikes that cascade to SLO
 * violations.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENOMEM} for {@code madvise} when addresses in the range {@code [addr,
 * addr+len)} are unmapped (not part of any existing VMA) or when the kernel cannot allocate the
 * internal resources needed to service the advice. On Linux, {@code MADV_WILLNEED} causes the
 * kernel to initiate readahead for file-backed mappings; if the page cache cannot accommodate the
 * readahead allocation, the kernel may return {@code -ENOMEM}.
 *
 * <p>A more common source of {@code ENOMEM} from {@code madvise} on Linux is advising a range that
 * contains an unmapped hole: if {@code [addr, addr+len)} partially overlaps with existing VMAs but
 * includes regions that are not mapped, the kernel returns {@code -ENOMEM} for the advice rather
 * than partially applying it. This behaviour differs from {@code MADV_DONTNEED}, which ignores
 * unmapped sub-ranges. Applications that advise large regions that may have gaps (e.g. sparse files
 * or partially-mapped databases) must handle {@code ENOMEM} from {@code madvise} correctly.
 *
 * <p>The critical correctness requirement is the same as for all {@code madvise} errors: the
 * failure is non-fatal. The data is still accessible via page faults; the application will observe
 * higher latency but not data loss or incorrectness. Applications that conflate {@code ENOMEM} from
 * {@code madvise} with {@code ENOMEM} from {@code mmap} and abort are over-reacting — assert that
 * the error-handling code discriminates between the two.
 *
 * <p>Compared with {@code EFAULT}: {@code ENOMEM} indicates the range contains unmapped regions
 * (partial VMA coverage failure) or the kernel lacks page-table resources; {@code EFAULT} indicates
 * the entire range extends outside the accessible address space. Both are non-fatal for advisory
 * hints.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEnomem(probability = 0.001)
 * class MadviseHoleTest {
 *   @Test
 *   void appHandlesEnomemOnMadviseWithoutFatalError(RedisConnectionInfo info) {
 *     // verify sparse-range ENOMEM from madvise does not abort the application
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; madvise failures are non-fatal so any
 * probability is safe from a correctness standpoint.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.ENOMEM)
public @interface ChaosMadviseEnomem {

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
   * @ChaosMadviseEnomem(id = "primary",  probability = 0.001)
   * @ChaosMadviseEnomem(id = "replica",  probability = 0.01)
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
    ChaosMadviseEnomem[] value();
  }
}
