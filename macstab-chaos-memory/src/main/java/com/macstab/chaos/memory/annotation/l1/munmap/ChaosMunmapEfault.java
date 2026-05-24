/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.munmap;

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
 * Injects {@code EFAULT} into {@code munmap} calls intercepted by libchaos-memory, causing the
 * calling code to observe a bad-address failure when attempting to unmap a memory region.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MUNMAP}, errno = {@code EFAULT}) tuple. The
 * {@code MUNMAP} selector intercepts {@code munmap} calls only, leaving {@code mmap}, {@code
 * mprotect}, and {@code madvise} unaffected. Compile-time safety: invalid selector/errno
 * combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code munmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code munmap} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EFAULT} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 14, {@code strerror}: "Bad
 *       address"; the memory region is NOT unmapped.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code munmap} returns {@code -1}; {@code errno = EFAULT} (14); the memory region remains
 *       mapped — the application must not treat the region as released and must not attempt to
 *       reuse its address for another mapping.
 *   <li>Memory managers and slab allocators that unmap regions on free paths must handle a failed
 *       {@code munmap} without leaking the region — the failed {@code munmap} is a VMA leak that
 *       inflates the process's virtual address usage; assert that the allocator tracks and retries
 *       or reports the leaked region.
 *   <li>Assert that the application surfaces a structured error that includes the address and
 *       length of the failed unmap — without this context, leaked VMAs are unattributable in
 *       post-mortem analysis.
 * </ul>
 *
 * Production failure mode: a native allocator or JVM that silently discards a failed {@code munmap}
 * result accumulates unmapped-but-still-mapped VMAs; over time the VMA count approaches {@code
 * vm.max_map_count}, causing subsequent {@code mmap} calls to fail with {@code ENOMEM} in an
 * apparently unrelated code path.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EFAULT} for {@code munmap} when the address range {@code [addr,
 * addr+len)} is not within the process's accessible address space. On Linux, the kernel validates
 * the range in {@code do_munmap}: if {@code addr} is not page-aligned, or if {@code addr + len}
 * overflows, or if the range extends beyond the process's address space limit, the kernel returns
 * {@code -EINVAL} (not {@code EFAULT}) — making real {@code EFAULT} from {@code munmap} extremely
 * rare in practice. However, Linux kernel 2.4 and earlier did return {@code EFAULT} for some of
 * these conditions, and other POSIX-compliant kernels may still do so.
 *
 * <p>The practical value of this annotation is to exercise the error-handling path for the rare but
 * spec-compliant {@code munmap} failure — a path that is almost universally absent in production
 * code. Most code ignores the return value of {@code munmap} entirely, relying on POSIX's statement
 * that "on success it is not possible for {@code munmap} to fail." This annotation reveals the
 * unhandled failure mode: if the interposer fires, the region is not unmapped, and any subsequent
 * code that reuses the address will either see stale data or receive {@code EBUSY}/{@code EINVAL}
 * from a mapping attempt that overlaps the live region.
 *
 * <p>The glibc {@code free} function, when it calls {@code munmap} internally (for chunks above
 * {@code MMAP_THRESHOLD}), ignores the return value of {@code munmap}. If the {@code munmap} fails,
 * glibc treats the chunk as freed but the kernel still considers the region mapped — creating a
 * divergence between glibc's internal state and the kernel's VMA table. This is a latent
 * double-accounting bug that only manifests when the address range is later reused by a new {@code
 * mmap} call that overlaps the un-released region.
 *
 * <p>Compared with {@code EINVAL}: both prevent the {@code munmap} from completing, but {@code
 * EFAULT} indicates the address range is structurally invalid (out of process space or not
 * page-aligned); {@code EINVAL} indicates the arguments are internally inconsistent (zero length or
 * invalid flag combination). Both are non-transient and require the caller to correct the address
 * or length before retrying.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMunmapEfault(probability = 0.00005)
 * class MunmapFailureTest {
 *   @Test
 *   void allocatorHandlesMunmapEfaultWithoutVmaLeak(RedisConnectionInfo info) {
 *     // verify VMA count does not grow unboundedly when munmap occasionally fails
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> very low (1e-5 to 1e-4); {@code EFAULT} at high rates
 * prevents the glibc allocator from returning memory to the OS, eventually causing virtual address
 * space exhaustion.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMunmapEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MUNMAP, errno = MmapErrno.EFAULT)
public @interface ChaosMunmapEfault {

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
   * @ChaosMunmapEfault(id = "primary",  probability = 0.001)
   * @ChaosMunmapEfault(id = "replica",  probability = 0.01)
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
    ChaosMunmapEfault[] value();
  }
}
