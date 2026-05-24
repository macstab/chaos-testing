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
 * Injects {@code ENOMEM} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe virtual-address-space or RAM+swap exhaustion
 * from any memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code ENOMEM}) tuple. The
 * {@code MMAP} selector is the broadest selector in the memory family: it intercepts all {@code
 * mmap} variants regardless of whether they are anonymous or file-backed. Use {@code
 * ChaosMmapAnonEnomem} to restrict injection to anonymous mappings only, or {@code
 * ChaosMmapFileEnomem} for file-backed mappings only. Compile-time safety: invalid selector/errno
 * combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap} call (any flags combination) the interposer runs a Bernoulli trial
 *       with probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 12, {@code strerror}:
 *       "Out of memory".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENOMEM} (12); glibc {@code malloc}
 *       returns {@code NULL} for large allocations, JVM direct allocators raise {@code
 *       OutOfMemoryError}, and file-mapping code (e.g. {@code mmap}-based file I/O) reports an I/O
 *       or memory error.
 *   <li>Both the allocator path and the memory-mapped file path are affected simultaneously —
 *       assert that the application handles allocation failures and file-mapping failures
 *       independently and gracefully.
 *   <li>Assert that no data is corrupted: applications that use memory-mapped files for persistence
 *       must not partially write data when the mapping fails.
 * </ul>
 *
 * Production failure mode: a process approaching the virtual address space limit (32-bit processes,
 * or 64-bit processes with very many mappings exceeding {@code vm.max_map_count}) fails all further
 * {@code mmap} calls with {@code ENOMEM} — affecting both the heap allocator and any file-mapping
 * operations the process performs.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code mmap} returns {@code MAP_FAILED} / {@code ENOMEM} when the kernel
 * cannot allocate the requested address space. For the broad {@code MMAP} selector this encompasses
 * both anonymous and file-backed mappings. The two paths diverge inside the kernel's {@code
 * do_mmap_pgoff}: for anonymous mappings, {@code ENOMEM} indicates virtual-address-space exhaustion
 * or overcommit accounting failure; for file-backed mappings, it additionally covers failure to
 * allocate the in-kernel {@code vm_area_struct} or page table entries.
 *
 * <p>The {@code vm.max_map_count} sysctl (default 65 536) caps the number of VMAs per process. JVMs
 * with large heap, code cache, metaspace, and many loaded shared libraries can approach this limit;
 * exceeding it causes every subsequent {@code mmap} — including those from {@code malloc} for large
 * allocations — to fail with {@code ENOMEM}. This is a realistic production incident in
 * long-running JVM services.
 *
 * <p>Under {@code vm.overcommit_memory=2} (strict accounting) the kernel refuses {@code mmap} when
 * the sum of committed virtual memory would exceed the configured limit. This affects anonymous
 * mappings more than file-backed ones (files are not counted against the commit charge).
 * Applications that run in cgroups with memory limits are particularly exposed: the cgroup memory
 * controller can reject charges at any time, returning {@code ENOMEM} regardless of system-wide
 * availability.
 *
 * <p>For memory-mapped file I/O, {@code ENOMEM} from {@code mmap} means the file's data cannot be
 * accessed via pointer arithmetic — a fallback to traditional {@code read}/{@code write} is
 * required. Applications that use memory-mapped files for zero-copy I/O (Kafka log segments,
 * RocksDB SST files, Chronicle Map entries) must handle this fallback gracefully. Many do not,
 * treating {@code MAP_FAILED} as an unrecoverable error.
 *
 * <p>Compared with the narrower selectors: {@code MMAP_ANON} intercepts only anonymous allocations
 * (heap path); {@code MMAP_FILE} intercepts only file-backed mappings (I/O path); {@code MMAP}
 * covers both — use it when testing combined behaviour under full {@code mmap} exhaustion, or use
 * the narrower selectors for more targeted fault isolation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEnomem(probability = 0.001)
 * class FullMmapExhaustionTest {
 *   @Test
 *   void appHandlesEnomemOnAllMappings(RedisConnectionInfo info) {
 *     // verify both allocation failures and file-mapping failures are handled gracefully
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 mirrors realistic OOM rates; 1.0 prevents
 * the container process from completing startup as both the heap allocator and shared-library
 * loader use {@code mmap}.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.ENOMEM)
public @interface ChaosMmapEnomem {

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
   * @ChaosMmapEnomem(id = "primary",  probability = 0.001)
   * @ChaosMmapEnomem(id = "replica",  probability = 0.01)
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
    ChaosMmapEnomem[] value();
  }
}
