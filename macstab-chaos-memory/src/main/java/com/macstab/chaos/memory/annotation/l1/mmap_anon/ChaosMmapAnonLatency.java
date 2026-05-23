/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_anon;

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
 * Adds {@link #delayMs} milliseconds of latency before every {@code mmap(MAP_ANONYMOUS)} call
 * intercepted by libchaos-memory, making the allocation succeed but take longer than expected.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, effect = LATENCY) tuple.
 * Unlike the errno variants, the latency primitive always delegates to the kernel and the mapping
 * succeeds; only wall-clock time is affected. Compile-time safety is preserved by the type system:
 * only valid selector/effect combinations have an annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer sleeps for {@link #delayMs}
 *       milliseconds before issuing the real kernel call.</li>
 *   <li>The kernel call is issued normally and the result (success or a real kernel error) is
 *       returned to the caller unchanged.</li>
 *   <li>The calling code observes that every large anonymous allocation takes at least
 *       {@link #delayMs} ms longer than without the rule.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>Allocation latency increases by at least {@link #delayMs} ms per intercepted call;
 *       application-level p99 latency for operations that trigger large allocations rises
 *       correspondingly.</li>
 *   <li>Connection-pool checkout timeouts, health-check deadlines, and request-processing
 *       timeouts may fire if the injected delay exceeds their budget — assert that timeouts
 *       are handled gracefully rather than causing unclean shutdowns.</li>
 *   <li>Assert latency SLOs explicitly: measure end-to-end operation time and verify it stays
 *       below an acceptable ceiling even under allocation stall.</li>
 * </ul>
 * Production failure mode: transparent hugepage (THP) compaction storms, NUMA balancing passes,
 * and memory pressure-induced kswapd activity can add tens to hundreds of milliseconds to
 * anonymous {@code mmap} calls without returning an error — an effect that is impossible to
 * reproduce without latency injection.
 *
 * <h2>Deep technical dive</h2>
 * <p>Anonymous {@code mmap} calls on Linux typically complete in under 10 microseconds when the
 * kernel is under no pressure. However, several kernel subsystems can introduce significant latency
 * without ever returning an error code. Transparent huge page compaction blocks the {@code mmap}
 * call while the kernel defragments physical memory to satisfy a 2 MB THP backing request.
 * NUMA balancing migrations pause application threads while pages are moved between NUMA nodes.
 * Kswapd reclaim activity under cgroup memory pressure can stall page-table updates inside
 * {@code do_mmap} for tens of milliseconds.
 *
 * <p>glibc's {@code malloc} calls {@code mmap} for allocations above {@code MMAP_THRESHOLD}
 * (default 128 KB, tunable via {@code mallopt(M_MMAP_THRESHOLD, ...)}). Applications that make
 * frequent large allocations (e.g. JSON parsers building large trees, video codecs allocating
 * frame buffers, JVM safepoints that expand the heap) are directly exposed to this latency.
 * Frameworks that pool native memory (Netty, gRPC) amortize the cost but still experience it
 * at pool initialisation time.
 *
 * <p>The JVM's own large allocations (metaspace expansion, code cache growth, G1 region
 * allocation) go through the same {@code mmap} path and will be affected. This can cause GC
 * pause times to spike and HotSpot compilation threads to stall, producing non-deterministic
 * throughput degradation that is difficult to attribute without systematic injection.
 *
 * <p>The latency primitive complements errno primitives: errno variants verify error-handling
 * correctness; latency variants verify timeout handling and SLO adherence under stall conditions.
 * Combine both to achieve full coverage of the allocation failure surface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonLatency(delayMs = 50)
 * class AllocationStallTest {
 *   @Test
 *   void operationLatencyRemainsWithinSlo(RedisConnectionInfo info) {
 *     long start = System.currentTimeMillis();
 *     // perform operations that trigger large allocations
 *     assertThat(System.currentTimeMillis() - start).isLessThan(500);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–100 ms mirrors realistic THP/NUMA stall events; values
 * above 1000 ms saturate connection-pool timeouts and produce cascading failures — intentional
 * in some scenarios, noisy in others.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosMmapAnonLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MMAP_ANON)
public @interface ChaosMmapAnonLatency {

  /**
   * Latency to inject before every matching {@code mmap(MAP_ANONYMOUS)} call, in milliseconds. Must
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
   * @ChaosMmapAnonLatency(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonLatency(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonLatency[] value();
  }
}
