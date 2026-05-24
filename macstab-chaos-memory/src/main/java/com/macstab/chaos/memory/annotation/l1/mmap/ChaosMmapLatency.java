/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap;

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
 * Adds {@link #delayMs} milliseconds of latency before every {@code mmap} call (anonymous and
 * file-backed) intercepted by libchaos-memory, making all memory-mapping operations succeed but
 * take longer than expected.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, effect = LATENCY) tuple. The {@code
 * MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use {@code
 * ChaosMmapAnonLatency} or {@code ChaosMmapFileLatency} to inject latency on only one path. Unlike
 * the errno variants, the latency primitive always delegates to the kernel and the mapping
 * succeeds; only wall-clock time is affected.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap} call (any flags) the interposer sleeps for {@link #delayMs}
 *       milliseconds before issuing the real kernel call.
 *   <li>The kernel call is issued normally and its result is returned to the caller unchanged.
 *   <li>Both heap allocations above {@code MMAP_THRESHOLD} and file-mapping operations take at
 *       least {@link #delayMs} ms longer than without the rule.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All large heap allocations and file-mapping operations are delayed by at least {@link
 *       #delayMs} ms; application end-to-end latency increases correspondingly.
 *   <li>Database engines that use memory-mapped files for I/O will see increased read and write
 *       latency; assert that operation SLOs are met or gracefully degraded.
 *   <li>Connection-pool timeouts and health-check deadlines may fire — assert graceful handling.
 * </ul>
 *
 * Production failure mode: under severe memory pressure, THP compaction, or NUMA rebalancing, both
 * heap allocations and file-mapping operations stall simultaneously, causing hidden latency spikes
 * that exhaust timeouts and produce cascading failures across the application.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The broad {@code MMAP} selector simultaneously delays all {@code mmap} call sites. This is the
 * most aggressive latency injection setting: it stresses both the allocator path (heap
 * fragmentation under pressure, GC stalls, JVM metaspace growth) and the file I/O path (database
 * SST file mapping, memory-mapped log segments, shared-library loading).
 *
 * <p>Applications that use memory-mapped files for zero-copy I/O (Kafka log segments, RocksDB SST
 * files, Chronicle Map) are particularly sensitive: each new segment mapping will be delayed, and
 * the cumulative effect can saturate producer and consumer throughput budgets. Applications that
 * also perform frequent large heap allocations (JSON parsers, video codec frame buffers) will see
 * both paths stressed simultaneously.
 *
 * <p>The JVM's internal allocators (code cache, metaspace, G1 heap region allocation) all use
 * {@code mmap} and will be delayed. This can cause GC pauses to elongate, JIT compilation threads
 * to stall, and class loading to slow — producing non-deterministic throughput degradation that is
 * difficult to attribute without systematic injection.
 *
 * <p>The latency primitive complements the errno primitives: errno variants verify error-handling
 * correctness; this variant verifies timeout handling and SLO adherence under combined allocation
 * and I/O stall conditions. Use the narrower selectors when testing only one path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapLatency(delayMs = 50)
 * class CombinedAllocationAndIoStallTest {
 *   @Test
 *   void operationLatencyRemainsWithinSlo(RedisConnectionInfo info) {
 *     long start = System.currentTimeMillis();
 *     // perform operations that trigger large allocations and file mappings
 *     assertThat(System.currentTimeMillis() - start).isLessThan(500);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–100 ms mirrors realistic THP/NUMA stall events; values
 * above 1000 ms saturate timeouts and produce cascading failures across both the allocator and the
 * file I/O path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosMmapLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MMAP)
public @interface ChaosMmapLatency {

  /**
   * Latency to inject before every matching {@code mmap} call, in milliseconds. Must be
   * non-negative. Zero is valid but produces no observable effect.
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
   * @ChaosMmapLatency(id = "primary",  probability = 0.001)
   * @ChaosMmapLatency(id = "replica",  probability = 0.01)
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
    ChaosMmapLatency[] value();
  }
}
