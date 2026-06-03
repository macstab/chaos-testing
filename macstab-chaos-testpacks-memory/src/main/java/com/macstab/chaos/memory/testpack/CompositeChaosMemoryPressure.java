/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Simulates intermittent allocation pressure by injecting {@code ENOMEM} on {@code mmap} calls
 * at a low but non-trivial probability (default 5%). Unlike the deterministic OOM-kill scenario
 * ({@link CompositeChaosOomKill}), this scenario targets the frustrating class of memory pressure
 * that is hard to reproduce: allocations fail sporadically, exercising defensive allocation paths
 * that almost no test suite covers.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#simulateMemoryPressure(container, 0.05)} via libchaos-memory.
 * This injects a {@code mmap:ERRNO:ENOMEM@0.05} rule — one in twenty anonymous or file-backed mmap
 * calls will fail. In production this class of pressure arises when a host approaches its memory
 * high-watermark, when a noisy-neighbour cgroup is competing, or when memory fragmentation prevents
 * large contiguous allocations even though total free memory is non-zero.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * A well-written service handles these failures: memory allocators fall back to smaller pools, thread
 * creation is retried, and glibc's arena machinery absorbs most pressure. Services that do not check
 * allocator return values or propagate {@code ENOMEM} correctly will exhibit
 * {@code NullPointerException}, silent data loss, or corrupted state. At 5% rate the impact is
 * noticeable but typically self-correcting if retries are implemented.
 *
 * <h2>Industry references</h2>
 *
 * <p>The 5% default is chosen from cgroups v2 memory.pressure guidance (PSI thresholds) and from
 * the Chaos Monkey design principle of using sub-catastrophic fault rates for sustained soak tests.
 * POSIX {@code mmap(2)} documents {@code ENOMEM} as the canonical allocation-failure errno. Netflix
 * Chaos Engineering (2016) and Google's Site Reliability Engineering book (chapter 22) both advocate
 * for low-rate fault injection as a standard pre-production gate.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosMemoryPressure
 * class AllocationFaultTest {
 *
 *   @Test
 *   void serverStaysResponsiveUnderAllocationPressure(GenericContainer<?> app) {
 *     // Drive load for 10 seconds; expect < 1% error rate despite allocation failures
 *     loadTest(app, Duration.ofSeconds(10));
 *     assertThat(errorRate()).isLessThan(0.01);
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosMemoryPressure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.MemoryPressureComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosMemoryPressure {

  /**
   * Probability that any intercepted {@code mmap} call returns {@code ENOMEM}. Must be in
   * {@code (0.0, 1.0]}. Default {@code 0.05} (5%).
   */
  double toxicity() default 0.05;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosMemoryPressure[] value();
  }
}
