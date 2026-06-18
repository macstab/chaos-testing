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
 * Injects {@code EINVAL} into {@code mprotect} calls intercepted by libchaos-memory, causing the
 * calling code to observe an invalid-argument failure when attempting to change the protection
 * attributes of a memory region.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MPROTECT}, errno = {@code EINVAL}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 22, {@code strerror}: "Invalid
 *       argument".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mprotect} returns {@code -1}; {@code errno = EINVAL} (22); the region's protection
 *       is unchanged — the application must not assume the new protection took effect.
 *   <li>Native code generators and JNA/JNI wrappers that construct protection bit masks dynamically
 *       must validate the mask before calling {@code mprotect}; assert that {@code EINVAL} is
 *       treated as a programming error and surfaces a diagnostic naming the invalid protection
 *       flags.
 *   <li>Assert that the application does not access memory with the assumed-but-not-granted
 *       protection after an {@code EINVAL} response — doing so can result in undefined behaviour or
 *       a silent security hole (writing to memory that was supposed to be made read-only).
 * </ul>
 *
 * Production failure mode: a platform upgrade introduces a kernel that enforces stricter
 * protection-combination validation (e.g. rejecting {@code PROT_WRITE | PROT_EXEC} on a W^X
 * kernel), causing previously successful {@code mprotect} calls from a native library to return
 * {@code EINVAL} without warning — a silent breaking change that only surfaces under the new
 * kernel.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EINVAL} for {@code mprotect} when the {@code addr} argument is not a
 * multiple of the system page size, or when invalid flags are set in {@code prot}. On Linux, the
 * kernel validates the alignment in {@code do_mprotect_pkey} and returns {@code -EINVAL} if {@code
 * addr & (PAGE_SIZE - 1)} is non-zero. The kernel also returns {@code EINVAL} when invalid
 * protection combinations are specified: on systems enforcing W^X (write-xor-execute), requesting
 * both {@code PROT_WRITE | PROT_EXEC} will return {@code EINVAL}.
 *
 * <p>JVM implementations are a significant source of {@code mprotect} calls: the JIT compiler uses
 * the W-then-X strategy (write compiled code with {@code PROT_READ | PROT_WRITE}, then call {@code
 * mprotect} to add {@code PROT_EXEC}). On kernels that enforce strict W^X, the intermediate state
 * with both write and execute permissions is prohibited, and the JVM must clear the write bit
 * before setting the execute bit. JVMs that do not implement this two-step transition will receive
 * {@code EINVAL} on W^X kernels.
 *
 * <p>Native libraries loaded via JNA or JNI may also call {@code mprotect} directly. A library that
 * hard-codes a protection mask including both {@code PROT_WRITE} and {@code PROT_EXEC} will fail on
 * W^X-enforcing platforms with {@code EINVAL}. This class of failure is common when migrating
 * workloads from x86-64 (which historically tolerated W^X) to ARM64 with strict W^X enforcement
 * (Apple Silicon, AWS Graviton security configurations).
 *
 * <p>Compared with {@code EACCES}: {@code EINVAL} indicates the arguments are structurally invalid
 * (wrong protection flags, misaligned address); {@code EACCES} indicates the credentials or policy
 * does not permit the requested protection on a valid target. A W^X policy may surface as either
 * {@code EINVAL} (invalid combination) or {@code EACCES} (policy denial) depending on the kernel
 * and LSM implementation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMprotectEinval(probability = 0.001)
 * class ProtectionFlagsTest {
 *   @Test
 *   void appHandlesEinvalOnMprotect(RedisConnectionInfo info) {
 *     // verify invalid-flag error is surfaced and no write occurs to the unprotected region
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; rates above 0.1 will deny all {@code
 * mprotect} calls including the JVM JIT startup transitions, preventing the JVM from making code
 * executable and causing process abort.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMprotectEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MPROTECT, errno = MmapErrno.EINVAL)
public @interface ChaosMprotectEinval {

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
   * @ChaosMprotectEinval(id = "primary",  probability = 0.001)
   * @ChaosMprotectEinval(id = "replica",  probability = 0.01)
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
    ChaosMprotectEinval[] value();
  }
}
