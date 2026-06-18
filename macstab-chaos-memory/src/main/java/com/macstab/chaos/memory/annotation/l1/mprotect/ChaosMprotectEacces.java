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
 * Injects {@code EACCES} into {@code mprotect} calls intercepted by libchaos-memory, causing the
 * calling code to observe a permission-denied failure when attempting to change the protection
 * attributes of a memory region.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MPROTECT}, errno = {@code EACCES}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = EACCES} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 13, {@code strerror}:
 *       "Permission denied".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mprotect} returns {@code -1}; {@code errno = EACCES} (13); the memory region's
 *       protection bits are unchanged — the application must not assume the new protection took
 *       effect and must surface a structured error.
 *   <li>JIT compilers and native code generators that transition memory regions from {@code
 *       PROT_WRITE} to {@code PROT_EXEC} (W-to-X flip) will fail with {@code EACCES} when LSM
 *       policies or W^X enforcement blocks the transition; assert that the JIT falls back
 *       gracefully and does not execute the unprotected region.
 *   <li>Assert that the application logs a message that names "Permission denied" and includes the
 *       protection bits requested, so operators can identify whether a SELinux/AppArmor policy
 *       change is needed.
 * </ul>
 *
 * Production failure mode: a tightened SELinux or AppArmor policy applied to a new container
 * revision blocks the {@code PROT_WRITE | PROT_EXEC} transition that a JIT compiler or a native
 * library loading stage depends on — causing silent fallback to interpreted mode or an unhandled
 * abort depending on the implementation.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EACCES} for {@code mprotect} when the requested protection conflicts
 * with the access mode under which the underlying file was opened (for file-backed mappings). The
 * most common case is requesting {@code PROT_WRITE} on a region backed by a file opened read-only,
 * or requesting {@code PROT_EXEC} when an LSM policy (SELinux {@code execmem} denial, AppArmor
 * {@code mprotect} rule) prohibits executable transitions.
 *
 * <p>On Linux, the kernel checks LSM hooks in {@code security_file_mprotect} before applying the
 * protection change. SELinux enforces the {@code execmem} permission when transitioning an
 * anonymous mapping to {@code PROT_EXEC}; AppArmor enforces its own {@code mprotect} rules. JVM
 * implementations that use a W-then-X write strategy for JIT output (write the code bytes with
 * {@code PROT_WRITE}, then flip to {@code PROT_READ | PROT_EXEC}) will be denied at the flip step
 * if the container's security context does not permit executable transitions on anonymous memory.
 *
 * <p>The practical implication is that containers deployed with a hardened security profile (e.g.
 * SELinux {@code enforcing} + no {@code execmem} grant) will cause the JVM's JIT to fail silently
 * or to fall back to interpreted execution. This degradation is often invisible in testing because
 * development environments run with permissive profiles. This annotation surfaces the failure mode
 * reproducibly.
 *
 * <p>Compared with {@code EPERM}: {@code EACCES} is a credentials/policy check against the
 * underlying object (the file or the LSM label); {@code EPERM} is a structural check that the
 * operation class is not permitted for this process regardless of the target object. In practice,
 * LSM policies return {@code EACCES} for most mprotect denials; kernel-level capability checks
 * (e.g. missing {@code CAP_SYS_PTRACE} for write access to a foreign process mapping) return {@code
 * EPERM}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMprotectEacces(probability = 0.001)
 * class ProtectionChangeTest {
 *   @Test
 *   void appHandlesEaccesOnMprotect(RedisConnectionInfo info) {
 *     // verify W-to-X transition failure does not execute unprotected code
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; rates above 0.01 will deny all {@code
 * mprotect} transitions including glibc internal protection management, causing process abort
 * during startup.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMprotectEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MPROTECT, errno = MmapErrno.EACCES)
public @interface ChaosMprotectEacces {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container in the test class)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-memory ({@code ERROR} fails at
   *     {@code beforeAll}; {@code ABORT} marks the test class YELLOW/aborted)
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMprotectEacces(id = "primary",  probability = 0.001)
   * @ChaosMprotectEacces(id = "replica",  probability = 0.01)
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
    ChaosMprotectEacces[] value();
  }
}
