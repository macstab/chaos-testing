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
 * Injects {@code EACCES} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe a permission-denied failure from any
 * memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code EACCES}) tuple. The
 * {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use {@code
 * ChaosMmapAnonEacces} or {@code ChaosMmapFileEacces} for narrower fault isolation. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EACCES} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 13, {@code strerror}:
 *       "Permission denied".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EACCES} (13); both the heap
 *       allocator and file-mapping paths are denied simultaneously.
 *   <li>glibc {@code malloc} propagates {@code NULL}; file-mapping code should fall back to {@code
 *       read}/{@code write} or surface a structured error — assert that no silent data loss occurs.
 *   <li>Assert that the application produces a permission-related diagnostic and does not retry
 *       indefinitely.
 * </ul>
 *
 * Production failure mode: a newly applied LSM policy (SELinux label change, AppArmor profile
 * update, or Kubernetes Security Context change) can deny {@code mmap} across all call sites
 * simultaneously, causing cascading failures in both the allocator and file-I/O paths.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EACCES} for {@code mmap} when the file descriptor's access mode is
 * incompatible with the requested protection. For file-backed mappings, this arises when the file
 * is opened read-only and {@code PROT_WRITE | MAP_SHARED} is requested. For anonymous mappings,
 * {@code EACCES} arises from LSM hooks that deny the operation based on security policy rather than
 * file permissions.
 *
 * <p>The broad {@code MMAP} selector injects {@code EACCES} on both code paths simultaneously. This
 * stresses applications more aggressively than the narrower selectors: a process that handles
 * allocation failure gracefully may still crash if its memory-mapped file path also fails at the
 * same time with a different errno than expected.
 *
 * <p>glibc's internal allocator (anonymous path), the dynamic linker (shared-library loading), and
 * any application code that uses {@code mmap} for file I/O are all affected. On JVM workloads,
 * class-loading via memory-mapped JAR files will fail — usually manifesting as {@code
 * ClassNotFoundException} or {@code NoClassDefFoundError} rather than a memory error.
 *
 * <p>Compared with {@code EPERM}: {@code EACCES} is a DAC/MAC credentials check failure (the object
 * is accessible in principle but not to this process); {@code EPERM} is a structural capability
 * check failure (the operation is globally disallowed for this process regardless of the object).
 * Both are non-transient: retry will not succeed without a privilege or policy change.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEacces(probability = 0.001)
 * class PolicyDenialTest {
 *   @Test
 *   void appHandlesPermissionDeniedOnAllMmaps(RedisConnectionInfo info) {
 *     // verify graceful error handling across both allocator and file-I/O paths
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; 1.0 will prevent the process from loading
 * shared libraries and mapping the JVM class path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EACCES)
public @interface ChaosMmapEacces {

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
   * @ChaosMmapEacces(id = "primary",  probability = 0.001)
   * @ChaosMmapEacces(id = "replica",  probability = 0.01)
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
    ChaosMmapEacces[] value();
  }
}
