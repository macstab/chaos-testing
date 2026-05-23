/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_anon;

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
 * Injects {@code EACCES} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe a permission-denied failure from anonymous memory allocation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code EACCES}) tuple.
 * Compile-time safety: this annotation exists only because {@code EACCES} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EACCES} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code sees the same result it would from a real security-policy denial:
 *       {@code MAP_FAILED} return, {@code errno} 13, {@code strerror}: "Permission denied".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EACCES} (13); applications that
 *       check the return value log "Permission denied" or equivalent.</li>
 *   <li>Allocators backed by anonymous mappings ({@code malloc}, JVM direct allocators) treat
 *       {@code EACCES} the same as {@code ENOMEM} — allocation returns {@code NULL} /
 *       {@code OutOfMemoryError}.</li>
 *   <li>Assert that the application returns a structured error rather than a crash or silent
 *       data corruption.</li>
 * </ul>
 * Production failure mode: LSM policies (SELinux, AppArmor) or seccomp profiles applied to a
 * newly deployed container revision can silently deny {@code mmap} for anonymous mappings,
 * causing the process to crash or behave incorrectly instead of reporting a clear error.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EACCES} for {@code mmap} when the file descriptor's access mode is
 * incompatible with the requested protection flags. For anonymous mappings ({@code MAP_ANONYMOUS})
 * there is no file descriptor; on Linux the kernel sets {@code EACCES} when an LSM (SELinux,
 * AppArmor, SMACK) denies the {@code mmap_file} hook even for anonymous regions or when the
 * process capability set is insufficient for the requested protection (e.g. {@code PROT_EXEC}
 * on a system with strict W^X policies via {@code vm.mmap_min_addr} or Yama restrictions).
 *
 * <p>In production, {@code EACCES} on anonymous {@code mmap} is most commonly observed when a
 * new container image ships a policy change that tightens seccomp or LSM rules, or when a
 * Kubernetes PodSecurityPolicy / Pod Security Admission controller drops capabilities that the
 * runtime previously relied on. The failure is silent unless the application explicitly checks
 * the return code of allocator primitives.
 *
 * <p>glibc's {@code malloc} propagates the failure to the caller as a {@code NULL} return; the
 * JVM raises {@code OutOfMemoryError} for direct buffers and code-cache expansions. Application
 * frameworks that pool native memory (Netty {@code PooledByteBufAllocator}, gRPC arena allocator)
 * typically do not distinguish {@code EACCES} from {@code ENOMEM} — they log the same message.
 *
 * <p>Compared with siblings: {@code EPERM} is the kernel's response to an operation that is
 * structurally disallowed regardless of credentials (e.g. mlock limit); {@code EACCES} is the
 * response to a credential or policy check failure on a specific object. Both surface as
 * "allocation failed" to libc callers but carry different diagnostic signals.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEacces(probability = 0.001)
 * class SecurityPolicyTest {
 *   @Test
 *   void appHandlesPermissionDeniedOnAlloc(RedisConnectionInfo info) {
 *     // drive allocations; assert graceful error rather than crash
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 mirrors realistic LSM-denial rates;
 * 1.0 prevents the container process from completing startup.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.EACCES)
public @interface ChaosMmapAnonEacces {

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
   * @ChaosMmapAnonEacces(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEacces(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEacces[] value();
  }
}
