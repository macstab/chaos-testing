/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mprotect;

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
 * Injects {@code ENOMEM} into {@code mprotect} calls intercepted by libchaos-memory, causing the
 * calling code to observe a virtual-memory exhaustion failure when attempting to change the
 * protection attributes of a memory region.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MPROTECT}, errno = {@code ENOMEM}) tuple.
 * The {@code MPROTECT} selector intercepts {@code mprotect} calls only, leaving {@code mmap},
 * {@code munmap}, and {@code madvise} unaffected. Compile-time safety: invalid selector/errno
 * combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mprotect} wrapper at the dynamic-linker level.
 *   <li>On each {@code mprotect} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12, {@code strerror}: "Out of
 *       memory (cannot allocate memory)".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mprotect} returns {@code -1}; {@code errno = ENOMEM} (12); the protection change did
 *       not take effect — the application must not assume the new protection is active and must not
 *       access the region with the assumed permissions.
 *   <li>JIT compilers that split a code arena VMA to apply per-method protection granularity will
 *       cause a VMA count increase; assert that the JIT handles {@code ENOMEM} from the split
 *       operation and falls back to coarser-grained protection without crashing.
 *   <li>Assert that the application surfaces the error with enough context (address range,
 *       protection bits, current VMA count if available) for an operator to determine whether the
 *       {@code vm.max_map_count} limit needs to be raised.
 * </ul>
 *
 * Production failure mode: a JIT or native memory manager applies fine-grained protection changes
 * across many small memory regions, causing the VMA count to approach {@code vm.max_map_count};
 * subsequent {@code mprotect} calls that would split an existing VMA into two smaller VMAs fail
 * with {@code ENOMEM} because the split creates a new VMA and exceeds the limit.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENOMEM} for {@code mprotect} when the kernel cannot allocate the
 * internal kernel data structures needed to split or merge VMAs. On Linux, changing the protection
 * of a sub-range of an existing VMA requires splitting the VMA into two or three pieces, each of
 * which is a new {@code struct vm_area_struct}. The kernel allocates the new VMA from a slab cache;
 * if the slab allocation fails (due to memory pressure or kernel memory accounting limits) the call
 * returns {@code -ENOMEM}.
 *
 * <p>A second path to {@code ENOMEM} from {@code mprotect} involves the {@code vm.max_map_count}
 * limit: when changing the protection of a range that spans the middle of an existing VMA, the
 * kernel must create a new VMA for the changed range (increasing the VMA count by at least one). If
 * the current VMA count is at {@code vm.max_map_count}, the split is rejected with {@code ENOMEM}.
 * This failure is distinct from {@code mmap} {@code ENOMEM} (which occurs because no free address
 * range exists) and is often confused with it in diagnostics.
 *
 * <p>JIT compilers that apply protection changes at the method granularity (one {@code mprotect}
 * call per compiled method) can create hundreds of VMAs per second during warmup. The G1 garbage
 * collector, Azul Zing, and GraalVM native images all use {@code mprotect} for read/write barriers
 * and protection isolation. Each call that splits a VMA increases the VMA count; without periodic
 * VMA consolidation (via adjacent same-protection VMA merging), the count grows without bound and
 * eventually causes {@code ENOMEM}.
 *
 * <p>Compared with {@code mmap} {@code ENOMEM}: both indicate kernel data structure exhaustion, but
 * they require different remediation. For {@code mmap} {@code ENOMEM}, releasing existing mappings
 * reduces the VMA count. For {@code mprotect} {@code ENOMEM}, releasing mappings also helps, but
 * the root cause is often a JIT or memory manager that applies protection changes at too fine a
 * granularity — requiring an application-level policy change.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMprotectEnomem(probability = 0.001)
 * class VmaLimitTest {
 *   @Test
 *   void appHandlesEnomemOnMprotect(RedisConnectionInfo info) {
 *     // verify JIT or native manager handles VMA-split failure and logs vm.max_map_count context
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; rates above 0.01 will prevent JVM JIT
 * code cache protection transitions, causing JVM startup failure.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMprotectEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MPROTECT, errno = MmapErrno.ENOMEM)
public @interface ChaosMprotectEnomem {

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
   * @ChaosMprotectEnomem(id = "primary",  probability = 0.001)
   * @ChaosMprotectEnomem(id = "replica",  probability = 0.01)
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
    ChaosMprotectEnomem[] value();
  }
}
