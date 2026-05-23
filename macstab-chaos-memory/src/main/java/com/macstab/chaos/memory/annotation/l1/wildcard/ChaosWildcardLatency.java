/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.wildcard;

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
 * Adds {@link #delayMs} milliseconds of latency before every VM syscall ({@code mmap},
 * {@code munmap}, {@code mprotect}, {@code madvise}) intercepted by libchaos-memory, making all
 * memory-management operations succeed but take longer than expected simultaneously.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code WILDCARD}, effect = LATENCY) tuple.
 * The {@code WILDCARD} selector is the broadest available: it matches every syscall interposed by
 * libchaos-memory — {@code mmap} (anonymous and file-backed), {@code munmap}, {@code mprotect},
 * and {@code madvise} — with a single rule. Unlike the errno variants, the latency primitive always
 * delegates to the kernel and the operation succeeds; only wall-clock time is affected. Use
 * narrower selectors for targeted stall testing ({@code MMAP_ANON} for allocation-only latency,
 * {@code MUNMAP} for TLB-shootdown simulation, {@code MPROTECT} for JIT protection stalls,
 * {@code MADVISE} for hint-path latency); use {@code WILDCARD} when you need to stress-test the
 * application against comprehensive, simultaneous slowdown across all memory-management paths.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc wrappers for {@code mmap}, {@code munmap}, {@code mprotect}, and
 *       {@code madvise} at the dynamic-linker level.</li>
 *   <li>On each intercepted call the interposer sleeps for {@link #delayMs} milliseconds before
 *       issuing the real kernel call.</li>
 *   <li>The kernel call is issued normally and its result is returned to the caller unchanged.</li>
 *   <li>Every memory-management operation succeeds but takes at least {@link #delayMs} ms longer
 *       than without the rule; the process remains functional but is memory-management-bound.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>All intercepted VM syscalls are delayed by at least {@link #delayMs} ms; no errors are
 *       injected, so error-handling code is not exercised — only timing-dependent code paths are
 *       stressed.</li>
 *   <li>Applications with latency-sensitive operation SLOs will observe deadline violations if
 *       any on-path VM syscall is delayed; assert that SLO violations surface as degraded-service
 *       responses rather than unhandled exceptions or connection drops.</li>
 *   <li>JVM processes performing concurrent GC ({@code munmap} for heap shrink, {@code madvise}
 *       for page reclaim, {@code mprotect} for card-table updates, {@code mmap} for GC region
 *       expansion) will experience all phases delayed simultaneously; assert that GC pause time
 *       SLOs remain within bounds and that the JVM does not fall back to stop-the-world GC due
 *       to the accumulated delay across all VM paths.</li>
 *   <li>Connection-pool health checks and heartbeat threads that share the JVM heap with GC
 *       threads may observe false timeouts when the GC's VM syscall latency delays the health
 *       check's own memory operations; assert that health-check timeouts are calibrated to
 *       account for worst-case memory-management latency under production-grade memory pressure.</li>
 * </ul>
 * Production failure mode: during a Kubernetes node memory-pressure event, the kernel's
 * transparent hugepage (THP) daemon initiates a compaction pass concurrently with I/O-intensive
 * processes; the compaction storm serialises all VM syscalls through the global mmap_lock for
 * seconds at a time — causing allocation, deallocation, protection, and advisory calls in all
 * processes to stall simultaneously, cascading to connection-pool exhaustion and SLO violations
 * across the entire node.
 *
 * <h2>Deep technical dive</h2>
 * <p>The {@code WILDCARD} latency rule fires on every intercepted VM syscall independently, not
 * once per request. A JVM in steady-state GC makes approximately 100–500 VM syscalls per second
 * (primarily {@code madvise} and {@code munmap} for heap management, with occasional {@code mmap}
 * for region expansion and {@code mprotect} for card-table generation boundary updates). With a
 * 50 ms wildcard delay, the GC threads collectively spend 5–25 seconds per second in injected
 * sleep — well beyond the available CPU budget. This rapidly degrades into stop-the-world GC
 * fallback as the concurrent phase cannot keep up.
 *
 * <p>The THP compaction scenario the wildcard latency simulates is particularly impactful: when
 * the kernel's {@code khugepaged} daemon compacts memory to form Huge Pages, it acquires the
 * per-mm {@code mmap_lock} in write mode for the duration of the collapse. All concurrent VM
 * syscalls from all threads of the affected process block until the lock is released — creating
 * a multi-second stall that affects {@code mmap}, {@code munmap}, {@code mprotect}, and
 * {@code madvise} simultaneously. The wildcard latency rule reproduces this correlation: all
 * four syscall types stall together, as they would during a THP compaction storm.
 *
 * <p>Databases that use memory-mapped I/O (RocksDB SST files, LMDB, LevelDB) and also allocate
 * write buffers ({@code mmap} anonymous) and call {@code mprotect} to protect read-only regions
 * will experience wildcard latency on all three operations. A 50 ms delay on {@code mmap} for
 * write buffer allocation, combined with a 50 ms delay on {@code mmap} for SST mapping and a
 * 50 ms delay on {@code munmap} for old segment eviction, will add 150 ms to every segment
 * rotation operation — well above the write stall threshold for RocksDB and likely triggering
 * write-stall or write-stop events even at low write rates.
 *
 * <p>The wildcard latency primitive complements the wildcard errno primitive: the errno variant
 * verifies that error handling exists and is correct on all VM paths simultaneously; the latency
 * variant verifies that SLOs and timeouts are calibrated for realistic worst-case memory-management
 * latency under production memory pressure. Both are necessary for comprehensive resilience coverage
 * of applications that rely on mmap-based I/O or JVM GC for memory management.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosWildcardLatency(delayMs = 50)
 * class VmLatencyTest {
 *   @Test
 *   void sloNotViolatedUnderWildcardMemoryManagementLatency(RedisConnectionInfo info) {
 *     // verify all memory-management paths are on the latency budget, not the critical path
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–50 ms mirrors realistic THP compaction stall durations;
 * values above 200 ms will accumulate across multiple VM syscalls per request and exceed most
 * application SLOs even at low request rates. Values above 1000 ms will trigger connection-pool
 * expiry in most JVM runtimes and cause cascading failures in dependent services.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosWildcardLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.WILDCARD)
public @interface ChaosWildcardLatency {

  /**
   * Latency to inject before every matching {@code every interposed VM syscall} call, in
   * milliseconds. Must be non-negative. Zero is valid but produces no observable effect.
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
   * @ChaosWildcardLatency(id = "primary",  probability = 0.001)
   * @ChaosWildcardLatency(id = "replica",  probability = 0.01)
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
    ChaosWildcardLatency[] value();
  }
}
