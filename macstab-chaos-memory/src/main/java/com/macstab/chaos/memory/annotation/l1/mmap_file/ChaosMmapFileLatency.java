/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_file;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryLatencyBinding;
import com.macstab.chaos.memory.model.MemorySelector;

/**
 * Adds {@link #delayMs} milliseconds of latency before every file-backed {@code mmap} call
 * intercepted by libchaos-memory, making all file-backed mapping operations succeed but take
 * longer than expected.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, effect = LATENCY) tuple.
 * The {@code MMAP_FILE} selector covers only file-backed {@code mmap} calls (those without
 * {@code MAP_ANONYMOUS}); use {@code ChaosMmapLatency} to add latency to both anonymous and
 * file-backed calls simultaneously. Unlike the errno variants, the latency primitive always
 * delegates to the kernel and the mapping succeeds; only wall-clock time is affected.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each file-backed {@code mmap} call the interposer sleeps for {@link #delayMs}
 *       milliseconds before issuing the real kernel call.</li>
 *   <li>The kernel call is issued normally and its result is returned to the caller unchanged.</li>
 *   <li>The mapping succeeds but takes at least {@link #delayMs} ms longer than without
 *       the rule; anonymous allocations are not affected.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>All file-backed mapping operations are delayed by at least {@link #delayMs} ms;
 *       anonymous heap allocations are unaffected, allowing isolation of the file I/O stall
 *       path from the allocator path.</li>
 *   <li>Database engines that use memory-mapped files for SST, WAL, or log segment I/O
 *       (RocksDB, LMDB, Kafka, Chronicle Queue) will see increased read and write latency
 *       proportional to the number of segment mappings established during the test; assert
 *       that operation SLOs are met or gracefully degraded.</li>
 *   <li>Connection-pool timeouts, health-check deadlines, and replication lag SLOs may fire;
 *       assert that these surface as structured warnings rather than unhandled exceptions.</li>
 * </ul>
 * Production failure mode: under network-filesystem (NFS, CIFS) degradation or kernel-level
 * page-cache pressure combined with NUMA rebalancing, file-backed {@code mmap} calls stall for
 * tens to hundreds of milliseconds — causing database compaction threads, log-segment rotation,
 * and shared-library loading to block and exhaust wall-clock budgets across the application.
 *
 * <h2>Deep technical dive</h2>
 * <p>File-backed {@code mmap} latency stalls arise from several kernel-level sources. When the
 * page cache does not contain the file's pages (cold start, cache eviction, or direct-I/O
 * bypass), the kernel must issue I/O to populate the pages before the mapping can be faulted;
 * this I/O can take tens of milliseconds on spinning disks or overloaded network storage.
 * The {@code mmap} call itself returns quickly (it only allocates a VMA), but the first access
 * to the mapped pages triggers a page fault and I/O wait — this is the "lazy" I/O cost that
 * this annotation simulates at the syscall boundary.
 *
 * <p>Applications that map new segments on hot paths (Kafka partition log rotation, RocksDB
 * compaction output, Chronicle Map growth) are particularly sensitive: each new segment mapping
 * delays the producing thread, which backs up producer queues and increases end-to-end latency
 * across the data pipeline. The delay injected here simulates the worst-case I/O cost at the
 * {@code mmap} call site, allowing tests to verify that SLOs hold even when new segment
 * mappings are slow.
 *
 * <p>The file-only selector ({@code MMAP_FILE}) allows precise isolation: by leaving anonymous
 * allocations unaffected, tests can attribute latency increases exclusively to the file I/O path.
 * This is valuable for profiling and for verifying that the fallback to {@code pread}/{@code pwrite}
 * (which does not involve {@code mmap}) correctly avoids the latency. Use {@code ChaosMmapLatency}
 * (the broad selector) when you want to stress both the allocator and the file I/O path
 * simultaneously.
 *
 * <p>The latency primitive complements the errno primitives: the errno variants verify
 * error-handling correctness when mapping fails; this variant verifies timeout handling and SLO
 * adherence when mapping is merely slow. Both are necessary for comprehensive resilience
 * coverage of applications that use memory-mapped file I/O.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileLatency(delayMs = 50)
 * class FileMappingStallTest {
 *   @Test
 *   void operationLatencyRemainsWithinSlo(RedisConnectionInfo info) {
 *     long start = System.currentTimeMillis();
 *     // perform operations that trigger file-backed segment mappings
 *     assertThat(System.currentTimeMillis() - start).isLessThan(500);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–100 ms mirrors realistic NFS/page-cache stall events;
 * values above 1000 ms saturate segment-rotation timeouts and produce cascading failures in
 * log-structured storage systems.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosMmapFileLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MMAP_FILE)
public @interface ChaosMmapFileLatency {

  /**
   * Latency to inject before every matching {@code mmap (file-backed)} call, in milliseconds. Must
   * be non-negative. Zero is valid but produces no observable effect.
   */
  long delayMs() default 50L;

  /**
   * Container id to bind this rule to. The default empty string {@code ""} applies the rule to
   * every memory-chaos-capable container in the test class. A non-empty id must match a container
   * annotation on the same test class, otherwise an {@code ExtensionConfigurationException} is
   * thrown at {@code beforeAll}.
   */
  String id() default "";

  /**
   * Policy applied when the active backend cannot honour the libchaos-memory requirement. {@link
   * OnMissingEnv#ERROR} fails the test class at {@code beforeAll}; {@link OnMissingEnv#ABORT}
   * raises a {@code TestAbortedException} (YELLOW in CI).
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMmapFileLatency(id = "primary",  probability = 0.001)
   * @ChaosMmapFileLatency(id = "replica",  probability = 0.01)
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
    ChaosMmapFileLatency[] value();
  }
}
