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
 * Injects {@code ENOMEM} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe a virtual-memory or page-table exhaustion failure when
 * attempting to establish a file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code ENOMEM}) tuple.
 * The {@code MMAP_FILE} selector intercepts only file-backed {@code mmap} calls (those without
 * {@code MAP_ANONYMOUS}), leaving anonymous allocations unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 12, {@code strerror}:
 *       "Out of memory (cannot allocate memory)".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENOMEM} (12); file-mapping code
 *       must fall back to conventional read/write I/O or reject the operation with a structured
 *       error — retrying is unlikely to succeed without releasing memory.
 *   <li>Database engines that use memory-mapped files as their primary I/O mechanism (RocksDB,
 *       LMDB, HaloDB, MapDB) must either fall back to {@code pread}/{@code pwrite} or close
 *       existing mappings before retrying; assert that the fallback path produces correct query
 *       results, not just absence of an exception.
 *   <li>Assert that applications which manage a memory-mapped cache (e.g. Kafka log segments,
 *       Chronicle Map) evict the oldest segment mapping and retry — verify that the eviction
 *       reduces the VMA count before the retry is issued.
 * </ul>
 *
 * Production failure mode: a long-running process accumulates file-backed VMAs across segment
 * rotation and compaction cycles without adequately releasing old mappings; as the VMA count
 * approaches {@code vm.max_map_count} (default 65536), new file-backed {@code mmap} calls begin
 * failing with {@code ENOMEM} — a failure that does not resolve until sufficient mappings are
 * released.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENOMEM} for file-backed {@code mmap} in two distinct cases: (1) the
 * process has reached the limit imposed by {@code RLIMIT_AS} (address space) or the process's
 * virtual address space is exhausted; (2) the number of VMAs in the process would exceed {@code
 * vm.max_map_count} (default 65536), which limits the number of distinct memory regions per process
 * to prevent unbounded kernel metadata growth.
 *
 * <p>For file-backed mappings, the {@code vm.max_map_count} limit is particularly important for
 * applications that create one mapping per file segment. Kafka brokers that manage thousands of log
 * segment files with per-segment {@code mmap} calls, RocksDB instances with large L0 and L1 layers
 * of SST files, and Chronicle Map databases with many regions can all approach this limit during
 * heavy write workloads. The kernel allocates a new VMA struct for each mapping; when the total
 * count across all anonymous and file-backed mappings reaches the limit, the next {@code mmap} call
 * returns {@code ENOMEM} regardless of available physical memory.
 *
 * <p>The page-table allocation failure is a separate code path: when the kernel successfully
 * allocates the VMA but fails to allocate a page table entry (PTE) for the mapping, it also returns
 * {@code ENOMEM} from {@code mmap}. This occurs when physical memory or swap is exhausted, or when
 * a cgroup memory controller rejects the page-table charge. The two sources of {@code ENOMEM} — VMA
 * limit and PTE allocation failure — require different remediation: VMA limit requires releasing
 * mappings; PTE failure requires adding memory or raising cgroup limits.
 *
 * <p>Compared with {@code EAGAIN}: {@code ENOMEM} from file-backed {@code mmap} is typically
 * structural — the process cannot establish the mapping without releasing existing resources or
 * changing system configuration. {@code EAGAIN} is transient — a retry after a brief pause may
 * succeed. Applications must implement different recovery strategies for the two errnos; treating
 * {@code ENOMEM} as transient leads to tight retry loops that exhaust CPU without making progress.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEnomem(probability = 0.001)
 * class FileMappingOomTest {
 *   @Test
 *   void appHandlesEnomemOnFileMappings(RedisConnectionInfo info) {
 *     // verify fallback to pread/pwrite produces correct results and no data loss
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 mirrors realistic VMA-limit exhaustion
 * rates; rates above 0.01 will prevent shared-library loading at process startup.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.ENOMEM)
public @interface ChaosMmapFileEnomem {

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
   * @ChaosMmapFileEnomem(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEnomem(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEnomem[] value();
  }
}
