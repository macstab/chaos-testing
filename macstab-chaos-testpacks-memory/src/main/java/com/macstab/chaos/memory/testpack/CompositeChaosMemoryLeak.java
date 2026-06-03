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
 * <p>Simulates gradual resource degradation by injecting {@code ENOMEM} at a very low rate (default
 * 0.1%) on every {@code mmap} call. This models the slow-burn memory exhaustion that accumulates over
 * hours or days in production: individual allocations almost always succeed, but occasional failures
 * over a long run reveal whether the service properly handles and propagates sporadic
 * {@code ENOMEM} — the class of bug that is invisible in unit tests and only surfaces in multi-day
 * soak runs.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#failLargeAllocation(container, 0.001)} via libchaos-memory.
 * This injects a {@code mmap:ERRNO:ENOMEM@0.001} rule — one in a thousand mmap calls fails. At this
 * rate the service remains fully functional for short tests, but memory-leak code paths are exercised
 * over extended runs. In production slow-leak conditions arise from gradual RSS growth, fragmentation
 * of the kernel slab allocator, or uncollected weak references in managed runtimes.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * At 0.1% the service is functional. The risk is latent: if {@code ENOMEM} returns are not checked,
 * pointers are null-dereferenced in low-frequency code paths that are only hit in long-running
 * processes. The primary goal is soak-test coverage, not immediate disruption.
 *
 * <h2>Industry references</h2>
 *
 * <p>The 0.1% default aligns with the Netflix Chaos Engineering recommendation for sustained
 * low-noise fault injection. The pattern is related to the "slow ramp" chaos design described in
 * Rosenthal et al., <em>Chaos Engineering</em> (O'Reilly, 2020). Linux {@code mmap(2)} guarantees
 * {@code ENOMEM} on virtual address exhaustion; POSIX requires callers to check the return value.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosMemoryLeak
 * class LeakSoakTest {
 *
 *   @Test
 *   void noNullDereferenceOverExtendedRun(GenericContainer<?> app) {
 *     // Run load for 60 seconds; service must not crash or log SIGSEGV
 *     loadTest(app, Duration.ofSeconds(60));
 *     assertThat(app.getLogs()).doesNotContain("SIGSEGV").doesNotContain("NullPointerException");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosMemoryLeak.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.MemoryLeakComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosMemoryLeak {

  /**
   * Probability that any intercepted {@code mmap} call returns {@code ENOMEM}. Must be in
   * {@code (0.0, 1.0]}. Default {@code 0.001} (0.1%) — designed for long-running soak tests.
   */
  double toxicity() default 0.001;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosMemoryLeak[] value();
  }
}
