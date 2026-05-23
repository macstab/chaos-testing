/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.munmap;

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
 * Adds {@link #delayMs} milliseconds of latency before every {@code munmap} call intercepted
 * by libchaos-memory, making all memory-release operations succeed but take longer than
 * expected.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MUNMAP}, effect = LATENCY) tuple.
 * The {@code MUNMAP} selector intercepts {@code munmap} calls only; use other latency
 * annotations for {@code mmap}, {@code mprotect}, or {@code madvise}. Unlike the errno variants,
 * the latency primitive always delegates to the kernel and the unmap succeeds; only wall-clock
 * time is affected.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code munmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code munmap} call the interposer sleeps for {@link #delayMs} milliseconds
 *       before issuing the real kernel call.</li>
 *   <li>The kernel call is issued normally and its result is returned to the caller unchanged.</li>
 *   <li>The unmap succeeds but takes at least {@link #delayMs} ms longer than without the rule.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>All {@code munmap} calls are delayed by at least {@link #delayMs} ms; the memory
 *       is eventually released; no error-handling code is exercised.</li>
 *   <li>Memory managers that call {@code munmap} on the deallocation path (glibc {@code free}
 *       for large chunks, Java NIO {@code DirectByteBuffer} cleanup, JVM heap shrink) will
 *       see deallocation throughput decrease; assert that application GC pause times or
 *       deallocation SLOs remain acceptable.</li>
 *   <li>Applications that release large memory-mapped segments on rotation (Kafka log segment
 *       deletion, RocksDB SST file eviction) will block the rotating thread for
 *       {@code numSegments * delayMs}; assert that the rotation thread does not exhaust
 *       its deadline and that readers are not blocked on the rotating thread's lock.</li>
 * </ul>
 * Production failure mode: under memory pressure, the kernel's TLB shootdown mechanism (which
 * is invoked by {@code munmap} to invalidate mappings on all CPUs sharing the address space)
 * stalls when many cores simultaneously execute TLB invalidation IPIs; on large NUMA systems
 * with many threads, {@code munmap} latency spikes to tens of milliseconds, blocking log
 * rotation and GC threads and causing cascading SLO violations.
 *
 * <h2>Deep technical dive</h2>
 * <p>The primary source of {@code munmap} latency in production is the TLB (Translation Lookaside
 * Buffer) shootdown: when a mapping is removed, the kernel must invalidate the TLB entries for
 * that mapping on every CPU that might have cached the translation. On a NUMA system with 128
 * cores, the TLB shootdown requires sending an inter-processor interrupt (IPI) to each core and
 * waiting for acknowledgement. Under high CPU utilisation, cores may not respond immediately,
 * causing the requesting thread to spin for milliseconds before all TLB entries are confirmed
 * invalidated.
 *
 * <p>Applications that frequently unmap large regions (database engines releasing SST files,
 * Kafka log segments being deleted) are most affected by TLB shootdown latency. The rotation
 * thread that calls {@code munmap} on a segment holds the segment's lock while the shootdown
 * completes; readers that need to access the segment are blocked for the full TLB shootdown
 * duration. This creates a latency spike in read throughput that is correlated with memory
 * release operations — a non-obvious production failure mode.
 *
 * <p>The JVM's G1 garbage collector releases heap regions to the OS by calling {@code madvise
 * MADV_FREE} or {@code munmap} during concurrent phases. If {@code munmap} stalls due to TLB
 * shootdown, the GC concurrent phase extends beyond its budget, increasing the probability of
 * a stop-the-world fallback. This annotation allows tests to verify that GC SLOs hold even
 * when memory release is slow.
 *
 * <p>The latency primitive complements the errno primitives: the errno variants verify
 * error-handling correctness when unmap fails; this variant verifies throughput and SLO
 * adherence when unmap is merely slow. Both are necessary for comprehensive coverage of
 * systems that perform frequent large-region memory releases.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMunmapLatency(delayMs = 30)
 * class SegmentRotationLatencyTest {
 *   @Test
 *   void segmentRotationDoesNotBlockReadersForLongerThanSlo(RedisConnectionInfo info) {
 *     // verify readers are not blocked for more than SLO during munmap-delayed segment rotation
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–50 ms mirrors realistic TLB-shootdown stall events on
 * large NUMA systems; values above 500 ms will prevent timely GC and cause stop-the-world
 * fallbacks in the JVM.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosMunmapLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MUNMAP)
public @interface ChaosMunmapLatency {

  /**
   * Latency to inject before every matching {@code munmap} call, in milliseconds. Must be
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
   * @ChaosMunmapLatency(id = "primary",  probability = 0.001)
   * @ChaosMunmapLatency(id = "replica",  probability = 0.01)
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
    ChaosMunmapLatency[] value();
  }
}
