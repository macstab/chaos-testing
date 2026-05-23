/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mprotect;

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
 * Adds {@link #delayMs} milliseconds of latency before every {@code mprotect} call intercepted
 * by libchaos-memory, making all memory-protection transitions succeed but take longer than
 * expected.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MPROTECT}, effect = LATENCY) tuple.
 * The {@code MPROTECT} selector intercepts {@code mprotect} calls only; use other latency
 * annotations for {@code mmap}, {@code munmap}, or {@code madvise}. Unlike the errno variants,
 * the latency primitive always delegates to the kernel and the protection change succeeds;
 * only wall-clock time is affected.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mprotect} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mprotect} call the interposer sleeps for {@link #delayMs} milliseconds
 *       before issuing the real kernel call.</li>
 *   <li>The kernel call is issued normally and its result is returned to the caller unchanged.</li>
 *   <li>The protection change succeeds but takes at least {@link #delayMs} ms longer than without
 *       the rule.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>All {@code mprotect} calls are delayed by at least {@link #delayMs} ms; the protection
 *       change itself succeeds, so no error-handling code is exercised.</li>
 *   <li>JIT compilers that call {@code mprotect} on the hot compilation path (W-to-X flip after
 *       each method compilation) will see JIT throughput decrease proportionally to the injected
 *       delay; assert that application throughput degrades gracefully and that JIT warmup SLOs
 *       are met or that the application falls back to interpreted mode without error.</li>
 *   <li>Memory managers that apply fine-grained protection policies across many regions will
 *       accumulate delay proportional to the number of {@code mprotect} calls; assert that
 *       SLOs for memory operations (allocation time, compaction time) remain within acceptable
 *       bounds under realistic protection-change rates.</li>
 * </ul>
 * Production failure mode: under CPU pressure or kernel lock contention, {@code mprotect} calls
 * stall while the kernel serialises VMA modification across all threads sharing the address space;
 * JIT compilation threads block waiting for the protection change, causing compiled code to be
 * unavailable and application threads to fall back to slow interpreted execution.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code mprotect} holds the {@code mmap_lock} (previously {@code mmap_sem}) in write mode
 * while modifying VMAs, which serialises all concurrent memory operations in the same process.
 * On multi-core systems with many threads calling {@code mprotect} concurrently (e.g. a JIT
 * compiler thread and multiple GC threads), lock contention can cause each {@code mprotect}
 * to wait tens of milliseconds while other threads complete their VMA modifications. This
 * annotation simulates that worst-case contention scenario without requiring a specific hardware
 * or kernel configuration.
 *
 * <p>JVM implementations that use the W-then-X pattern for JIT code management are most
 * affected: each compiled method requires at least one {@code mprotect} call to clear the write
 * bit and set the execute bit. On a busy JVM with aggressive JIT compilation (many threads
 * reaching new code paths simultaneously), the {@code mprotect} rate can exceed 1000 calls/sec
 * during warmup. Injecting even a 10ms delay can extend JVM warmup from seconds to minutes.
 *
 * <p>The latency primitive is particularly useful for verifying timeout behaviour in frameworks
 * that have an implicit assumption that {@code mprotect} is fast. Connection-pool health checks
 * and circuit-breaker timeouts that fire during JVM warmup (when {@code mprotect} contention
 * is highest) are a common source of false-positive alerts in production. This annotation
 * allows tests to reproduce that scenario deterministically.
 *
 * <p>The latency primitive complements the errno primitives: the errno variants verify
 * error-handling correctness when protection changes fail; this variant verifies timeout
 * handling and throughput degradation when changes are merely slow. Both are necessary for
 * comprehensive coverage of systems that rely on {@code mprotect} in performance-critical paths.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMprotectLatency(delayMs = 20)
 * class JitWarmupLatencyTest {
 *   @Test
 *   void applicationMeetsWarmupSloUnderMprotectContention(RedisConnectionInfo info) {
 *     // verify JIT warmup completes within SLO even with 20ms mprotect delay
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–50 ms mirrors realistic kernel lock-contention stall
 * events; values above 500 ms will prevent the JVM from completing JIT warmup within typical
 * test timeouts.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosMprotectLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MPROTECT)
public @interface ChaosMprotectLatency {

  /**
   * Latency to inject before every matching {@code mprotect} call, in milliseconds. Must be
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
   * @ChaosMprotectLatency(id = "primary",  probability = 0.001)
   * @ChaosMprotectLatency(id = "replica",  probability = 0.01)
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
    ChaosMprotectLatency[] value();
  }
}
