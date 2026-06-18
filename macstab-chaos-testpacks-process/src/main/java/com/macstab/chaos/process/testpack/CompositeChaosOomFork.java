/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Every {@code fork()} call fails with {@code ENOMEM} at the configured probability, simulating
 * the kernel failing to allocate the memory structures (page tables, task_struct, kernel stack)
 * required for a new process during an OOM event. Unlike {@code EAGAIN}, which indicates a
 * process-count limit, {@code ENOMEM} from {@code fork} indicates that the kernel ran out of
 * allocatable memory for the child — a more severe condition that may persist until the OOM killer
 * has freed memory.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.ENOMEM, toxicity)} via
 * libchaos-process. In production this happens during memory-pressure events: overcommitted nodes,
 * large heap applications pushing the kernel's slab allocator to the edge, or JVM GC pauses
 * coinciding with a fork-heavy workload.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Applications that treat fork-ENOMEM as permanent (vs. transient) will stop spawning workers even
 * after memory pressure subsides. Unlike fork-EAGAIN, ENOMEM is not always transient — the OOM
 * killer may still be active — so the correct recovery is to wait and retry with exponential
 * backoff rather than immediately retrying. Applications without this distinction in their
 * error-handling path will behave identically for both errnos and may recover too aggressively
 * under ENOMEM.
 *
 * <h2>Industry references</h2>
 *
 * <p>The difference between {@code EAGAIN} and {@code ENOMEM} from {@code fork} is documented in
 * the Linux {@code fork(2)} man-page. Google's OOM-kill post-mortems (SRE book, Chapter 17)
 * emphasise the importance of distinguishing transient from permanent resource failures in
 * error-handling code; fork-ENOMEM is the canonical example where the distinction changes recovery
 * behaviour.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosOomFork(toxicity = 0.5)
 * class OomForkTest {
 *   @Test
 *   void workerAllocationRetriesWithBackoffOnEnomem() {
 *     // assert: ENOMEM distinguished from EAGAIN; exponential backoff applied
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosOomFork.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.OomForkComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosOomFork {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code ENOMEM} fires on each {@code fork()} call.
   * Defaults to {@code 0.5} (half of fork attempts fail with OOM).
   */
  double toxicity() default 0.5;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosOomFork[] value();
  }
}
