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
 * Injects {@code EFAULT} into {@code mprotect} calls intercepted by libchaos-memory, causing
 * the calling code to observe a bad-address failure when attempting to change the protection
 * attributes of a memory region.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MPROTECT}, errno = {@code EFAULT})
 * tuple. The {@code MPROTECT} selector intercepts {@code mprotect} calls only, leaving
 * {@code mmap}, {@code munmap}, and {@code madvise} unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mprotect} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mprotect} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EFAULT} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 14,
 *       {@code strerror}: "Bad address".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mprotect} returns {@code -1}; {@code errno = EFAULT} (14); the region's protection
 *       is unchanged — the application must not assume the protection change took effect and must
 *       not access the region with the assumed (but not granted) protection.</li>
 *   <li>JIT compilers and native memory managers that compute region addresses from base pointer
 *       arithmetic must assert that the computed address is within a live mapping before calling
 *       {@code mprotect}; assert that an {@code EFAULT} response triggers address recomputation
 *       rather than a crash.</li>
 *   <li>Assert that the application surfaces a structured error message that includes the
 *       address and length passed to {@code mprotect} so operators can diagnose stale address
 *       calculation bugs from logs.</li>
 * </ul>
 * Production failure mode: a native code generator or JIT that caches the base address of a
 * code arena passes a stale address to {@code mprotect} after the arena has been unmapped and
 * reallocated by the runtime; the kernel returns {@code EFAULT} because the stale address no
 * longer refers to a live mapping, causing the protection change to silently fail.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EFAULT} for {@code mprotect} when the {@code addr} parameter points
 * to an address not in the process's accessible address space, or when {@code addr + len}
 * overflows the address space. On Linux, the kernel validates the range in
 * {@code mprotect_fixup} by walking the VMA list; if any part of {@code [addr, addr+len)} is
 * not covered by a VMA, the kernel returns {@code -EFAULT}.
 *
 * <p>Real {@code EFAULT} from {@code mprotect} is rare in well-structured code because the
 * caller always obtains the address from a prior {@code mmap} return value. The failure mode
 * arises when the address is computed indirectly: a JIT that stores the start address of a code
 * region in a field and later passes it to {@code mprotect} for the W-to-X flip can produce
 * a stale address if a concurrent GC or arena reset unmapped the region between the store and
 * the {@code mprotect} call. This race is extremely difficult to reproduce in normal testing.
 *
 * <p>An arithmetic path to {@code EFAULT} exists when {@code addr + len} wraps around the
 * 64-bit address space: if {@code addr} is near {@code ULONG_MAX} and {@code len} is large,
 * the addition overflows, and the kernel returns {@code EFAULT}. This is a latent integer
 * overflow in the address-range computation that only manifests with very large mappings.
 * Languages that use 32-bit integers for memory sizes and promote them to 64-bit without
 * sign-extension are at risk.
 *
 * <p>Compared with {@code EINVAL}: {@code EFAULT} occurs when the address range is not
 * accessible (structural VMA gap or address overflow); {@code EINVAL} occurs when the
 * protection flags are structurally invalid (e.g. {@code PROT_GROWSDOWN | PROT_EXEC} on an
 * architecture that doesn't support it). Both are non-transient and indicate a programming
 * error in the calling code.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMprotectEfault(probability = 0.00005)
 * class StaleAddressTest {
 *   @Test
 *   void appHandlesEfaultOnMprotect(RedisConnectionInfo info) {
 *     // verify stale-address EFAULT is logged with address and len, and no crash follows
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> very low (1e-5 to 1e-4); {@code EFAULT} at high
 * rates denies all protection transitions, causing process abort during JVM startup when the
 * JIT code cache cannot be made executable.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMprotectEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MPROTECT, errno = MmapErrno.EFAULT)
public @interface ChaosMprotectEfault {

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
   * @ChaosMprotectEfault(id = "primary",  probability = 0.001)
   * @ChaosMprotectEfault(id = "replica",  probability = 0.01)
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
    ChaosMprotectEfault[] value();
  }
}
