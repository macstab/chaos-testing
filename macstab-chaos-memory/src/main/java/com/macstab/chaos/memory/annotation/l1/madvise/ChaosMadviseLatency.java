/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.madvise;

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
 * Adds {@link #delayMs} milliseconds of latency before every {@code madvise} call intercepted
 * by libchaos-memory, making all memory-hint operations succeed but take longer than expected.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, effect = LATENCY) tuple.
 * The {@code MADVISE} selector intercepts {@code madvise} calls only; use other latency
 * annotations for {@code mmap}, {@code munmap}, or {@code mprotect}. Unlike the errno variants,
 * the latency primitive always delegates to the kernel and the hint is applied; only wall-clock
 * time is affected.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code madvise} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code madvise} call the interposer sleeps for {@link #delayMs} milliseconds
 *       before issuing the real kernel call.</li>
 *   <li>The kernel call is issued normally and its result is returned to the caller unchanged.</li>
 *   <li>The hint is applied but takes at least {@link #delayMs} ms longer than without the rule.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>All {@code madvise} calls are delayed by at least {@link #delayMs} ms; the hint itself
 *       succeeds, so no error-handling code is exercised.</li>
 *   <li>Applications that call {@code madvise(MADV_WILLNEED)} on the critical path before
 *       latency-sensitive reads will see their pre-fault benefit delayed; assert that operation
 *       SLOs account for the pre-fault delay and that the fallback (on-demand paging) does not
 *       cause unacceptable latency spikes when the pre-fault arrives late.</li>
 *   <li>JVMs that call {@code madvise(MADV_FREE)} or {@code madvise(MADV_DONTNEED)} during GC
 *       will see GC concurrent phases extended by the injected delay; assert that GC pause times
 *       remain within SLOs and that the JVM does not fall back to stop-the-world GC due to the
 *       extended concurrent phase.</li>
 * </ul>
 * Production failure mode: under memory pressure, the kernel's {@code MADV_WILLNEED} readahead
 * stalls while waiting for I/O to complete; the calling thread blocks for tens to hundreds of
 * milliseconds in the kernel rather than returning immediately after scheduling the readahead —
 * causing latency spikes on threads that call {@code madvise} on hot paths.
 *
 * <h2>Deep technical dive</h2>
 * <p>Most {@code madvise} calls are designed to be non-blocking hints — the kernel queues the
 * request and returns immediately. However, {@code MADV_WILLNEED} can block when the kernel
 * decides to immediately service the readahead rather than deferring it; this is particularly
 * common when the process's working set exceeds available physical memory and the kernel
 * compacts memory while servicing the readahead. The calling thread may block in the kernel
 * for the duration of a memory compaction event.
 *
 * <p>Applications that use {@code madvise(MADV_FREE)} in a GC finaliser thread are also
 * susceptible to latency: the kernel's lazy-free tracking may require acquiring a spinlock
 * under contention when many pages are being concurrently freed across multiple threads.
 * On a JVM with 64 GC threads each calling {@code madvise(MADV_FREE)}, spinlock contention
 * can serialise all 64 threads, extending the GC concurrent phase by a factor proportional
 * to the number of threads.
 *
 * <p>The latency primitive is particularly valuable for testing that {@code madvise} is not
 * called on latency-critical paths: if injecting a 50ms delay causes an SLO violation, the
 * application is incorrectly treating an advisory hint as a synchronous prerequisite. This
 * annotation reveals that design flaw reproducibly. The fix is either to call {@code madvise}
 * asynchronously (from a background thread) or to accept that the hint may arrive too late.
 *
 * <p>The latency primitive complements the errno primitives: the errno variants verify
 * error-handling correctness when hints fail; this variant verifies that hints are treated as
 * asynchronous best-effort operations rather than synchronous prerequisites. Both are necessary
 * for comprehensive resilience coverage of systems that use {@code madvise} for performance.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseLatency(delayMs = 50)
 * class MadviseOnCriticalPathTest {
 *   @Test
 *   void operationSloNotExceededWhenMadviseIsDelayed(RedisConnectionInfo info) {
 *     // verify madvise delay does not cause SLO violation (hints must be async)
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–100 ms mirrors realistic memory-compaction stall
 * events; values above 500 ms will extend GC concurrent phases enough to trigger stop-the-world
 * fallbacks in most JVM configurations.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosMadviseLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MADVISE)
public @interface ChaosMadviseLatency {

  /**
   * Latency to inject before every matching {@code madvise} call, in milliseconds. Must be
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
   * @ChaosMadviseLatency(id = "primary",  probability = 0.001)
   * @ChaosMadviseLatency(id = "replica",  probability = 0.01)
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
    ChaosMadviseLatency[] value();
  }
}
