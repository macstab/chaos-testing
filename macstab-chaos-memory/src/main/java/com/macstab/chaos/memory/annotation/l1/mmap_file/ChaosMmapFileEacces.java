/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_file;

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
 * Injects {@code EACCES} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe a permission-denied failure when attempting to establish
 * a file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code EACCES})
 * tuple. The {@code MMAP_FILE} selector intercepts only file-backed {@code mmap} calls
 * (those without {@code MAP_ANONYMOUS}), leaving anonymous allocations unaffected. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EACCES} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 13,
 *       {@code strerror}: "Permission denied".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EACCES} (13); file-mapping code
 *       must fall back to conventional read/write I/O or surface a structured error.</li>
 *   <li>Applications that open files in read-only mode and attempt {@code PROT_WRITE | MAP_SHARED}
 *       will normally receive real {@code EACCES} — this annotation simulates the same condition
 *       stochastically even on files opened with the correct mode.</li>
 *   <li>Assert that no data corruption occurs on the fallback path and that the application
 *       logs a descriptive error rather than silently producing incorrect results.</li>
 * </ul>
 * Production failure mode: a security policy change (file ACL tightened, SELinux label changed,
 * or volume remounted read-only) causes all existing file-backed mappings to fail on the next
 * access and all new mapping attempts to return {@code EACCES} — typically observed in database
 * engines and log-structured storage systems.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EACCES} for file-backed {@code mmap} when the file is not open for
 * reading (when {@code PROT_READ} is requested) or when the file is not open for writing and
 * {@code MAP_SHARED | PROT_WRITE} is requested. The kernel validates the fd's access mode against
 * the requested protection in {@code do_mmap_pgoff} before establishing the mapping.
 *
 * <p>In production, {@code EACCES} on file-backed mappings most commonly arises when a process
 * inherits a file descriptor from a parent that opened the file with insufficient permissions,
 * or when a filesystem is remounted read-only (e.g. due to an I/O error triggering the kernel's
 * auto-remount-ro feature) while a process holds a writable mapping open.
 *
 * <p>Applications that use memory-mapped files for write-ahead logs, SST files, or crash-safe
 * journals must handle {@code EACCES} on their mapping calls — the alternative (mapping with
 * {@code MAP_PRIVATE} and syncing changes back via {@code write}) is a safe fallback but
 * sacrifices the zero-copy benefit. Many implementations do not implement this fallback.
 *
 * <p>Compared with {@code EPERM}: {@code EACCES} is a credentials check (this process does not
 * have the right to access this object in this mode); {@code EPERM} is a structural check (the
 * operation itself is disallowed for this process class regardless of the object). Both are
 * non-transient for file-backed mappings.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEacces(probability = 0.001)
 * class FileMappingPermissionTest {
 *   @Test
 *   void appHandlesEaccesOnFileMappings(RedisConnectionInfo info) {
 *     // verify the application falls back to read/write I/O and produces a structured error
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; 1.0 will prevent the process from
 * loading shared libraries and mapping the JVM class path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.EACCES)
public @interface ChaosMmapFileEacces {

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
   * @ChaosMmapFileEacces(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEacces(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEacces[] value();
  }
}
