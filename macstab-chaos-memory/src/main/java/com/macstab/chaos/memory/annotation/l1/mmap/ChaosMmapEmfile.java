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
 * Injects {@code EMFILE} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe a per-process file-descriptor limit failure
 * from any memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code EMFILE}) tuple. The
 * {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use {@code
 * ChaosMmapAnonEmfile} or {@code ChaosMmapFileEmfile} for narrower fault isolation. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EMFILE} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 24, {@code strerror}:
 *       "Too many open files".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EMFILE} (24); both heap allocations
 *       and file-mapping operations are affected simultaneously.
 *   <li>glibc {@code malloc} propagates {@code NULL}; file-mapping code should surface a
 *       resource-limit diagnostic and close unused descriptors.
 *   <li>Assert that resource-limit recovery logic (closing unused connections, shrinking pools)
 *       fires correctly and that the application degrades gracefully under fd pressure.
 * </ul>
 *
 * Production failure mode: long-lived processes with file-descriptor leaks (connections not closed,
 * temporary files not unlinked, memory-mapped files not unmapped) accumulate descriptors until
 * {@code RLIMIT_NOFILE} is reached; all subsequent {@code open}, {@code socket}, and internally
 * file-backed {@code mmap} calls then fail with {@code EMFILE}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EMFILE} when the per-process open-file limit ({@code RLIMIT_NOFILE})
 * would be exceeded. For file-backed {@code mmap}, the file descriptor must remain open for the
 * lifetime of the mapping; on Linux, the mapping itself does not consume an additional descriptor
 * (the kernel increments the file's reference count, not the process's fd table). However, {@code
 * EMFILE} can arise from the {@code mmap} call if the kernel's internal file-table management needs
 * to allocate a new file-description structure.
 *
 * <p>For the broad {@code MMAP} selector, both anonymous and file-backed paths receive {@code
 * EMFILE}. This stresses resource-management code more aggressively: a process that tries to
 * recover from a failed file mapping by opening an alternative file will also fail that {@code
 * open} if the fd table is truly exhausted.
 *
 * <p>The most realistic scenario for {@code EMFILE} on file-backed {@code mmap} is an application
 * that maps many files (e.g. a database that memory-maps its SST files) and does not close old
 * mappings before creating new ones. The file descriptor count grows monotonically until {@code
 * EMFILE} is reached — a resource leak that is difficult to detect without monitoring.
 *
 * <p>Compared with {@code ENFILE}: {@code EMFILE} is per-process (one process hit its own limit);
 * {@code ENFILE} is system-wide (the entire host is exhausted). Both cause identical observable
 * failures but require different remediation strategies.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEmfile(probability = 0.001)
 * class FdLeakDetectionTest {
 *   @Test
 *   void appHandlesEmfileOnAllMmaps(RedisConnectionInfo info) {
 *     // verify resource-limit recovery fires and the application degrades gracefully
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; combine with a reduced {@code
 * RLIMIT_NOFILE} in the container for maximum realism.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EMFILE)
public @interface ChaosMmapEmfile {

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
   * @ChaosMmapEmfile(id = "primary",  probability = 0.001)
   * @ChaosMmapEmfile(id = "replica",  probability = 0.01)
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
    ChaosMmapEmfile[] value();
  }
}
