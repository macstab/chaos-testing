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
 * Injects {@code EINVAL} into {@code munmap} calls intercepted by libchaos-memory, causing the
 * calling code to observe an invalid-argument failure when attempting to unmap a memory region.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MUNMAP}, errno = {@code EINVAL})
 * tuple. The {@code MUNMAP} selector intercepts {@code munmap} calls only, leaving {@code mmap},
 * {@code mprotect}, and {@code madvise} unaffected. Compile-time safety: invalid selector/errno
 * combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code munmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code munmap} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 22,
 *       {@code strerror}: "Invalid argument"; the memory region is NOT unmapped.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code munmap} returns {@code -1}; {@code errno = EINVAL} (22); the memory region
 *       remains mapped — the application must not treat the region as released.</li>
 *   <li>Memory managers that compute the unmap address from allocation metadata must validate
 *       page alignment before calling {@code munmap}; assert that {@code EINVAL} triggers a
 *       diagnostic that includes the unaligned address so that operators can identify the
 *       alignment bug from logs.</li>
 *   <li>Assert that a failed {@code munmap} does not cause the allocator to double-free or to
 *       reuse the address range before verifying that the unmap succeeded — doing so causes
 *       use-after-free or silent memory corruption in a subsequent allocation.</li>
 * </ul>
 * Production failure mode: a memory manager that truncates a 64-bit address to 32 bits before
 * passing it to {@code munmap} (an integer width bug in native code) produces a non-page-aligned
 * address that the kernel rejects with {@code EINVAL}; the manager silently treats the chunk as
 * freed while the kernel still holds the original mapping, causing a VMA leak that eventually
 * exhausts the address space.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EINVAL} for {@code munmap} when the {@code addr} argument is not
 * aligned to the system page size (a multiple of {@code sysconf(_SC_PAGESIZE)}), when
 * {@code len} is zero, or when the address range {@code [addr, addr+len)} wraps around the
 * address space. On Linux, {@code do_munmap} checks alignment and zero-length in this order:
 * non-page-aligned {@code addr} returns {@code -EINVAL}; zero {@code len} returns {@code -EINVAL};
 * address-space wrap returns {@code -EINVAL}.
 *
 * <p>The most common source of {@code EINVAL} from {@code munmap} in production code is an
 * integer-width mismatch: JNI code that stores an address as {@code jint} rather than
 * {@code jlong} will pass the truncated value to native {@code munmap} when freeing a
 * Java-allocated direct buffer. If the original address had bits set above bit 31, the
 * truncation produces an unaligned value and {@code munmap} returns {@code EINVAL}. The
 * region at the original address is never freed; the region at the truncated address may or
 * may not be mapped.
 *
 * <p>A zero-length {@code munmap} (arising from an arithmetic error in the size calculation)
 * is also rejected with {@code EINVAL}. A database engine that computes the size of a segment
 * to unmap from the difference of two file positions can produce zero if the positions are
 * equal; the {@code munmap} call is silently rejected and the segment accumulates as a VMA
 * leak.
 *
 * <p>Compared with {@code EFAULT}: {@code EINVAL} indicates the arguments are structurally
 * invalid (misaligned address, zero length, or address overflow); {@code EFAULT} indicates
 * the address range is inaccessible to the kernel (extends beyond the accessible address space).
 * On modern Linux, the kernel returns {@code EINVAL} for both misalignment and overflow
 * conditions; {@code EFAULT} from {@code munmap} is effectively unreachable on current kernels.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMunmapEinval(probability = 0.001)
 * class MunmapAlignmentTest {
 *   @Test
 *   void allocatorHandlesMunmapEinvalWithoutLeak(RedisConnectionInfo info) {
 *     // verify EINVAL triggers diagnostic with address/len and no double-free follows
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; rates above 0.01 prevent the glibc
 * allocator from returning memory to the OS, causing virtual address space exhaustion.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMunmapEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MUNMAP, errno = MmapErrno.EINVAL)
public @interface ChaosMunmapEinval {

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
   * @ChaosMunmapEinval(id = "primary",  probability = 0.001)
   * @ChaosMunmapEinval(id = "replica",  probability = 0.01)
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
    ChaosMunmapEinval[] value();
  }
}
