/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates sustained memory exhaustion in a Spring Boot JVM: both anonymous and file-backed
 * memory mappings fail at rate {@code pressureRate}, and an OutOfMemoryError is injected via the
 * JVM chaos layer to reproduce the application-level symptom of heap exhaustion preceding an OOM
 * kill.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Memory: MMAP_ANON → ENOMEM at probability {@code pressureRate} — anonymous JVM heap
 *       allocations fail; GC cannot reclaim fast enough; OOME becomes inevitable
 *   <li>Memory: MMAP → ENOMEM at probability {@code pressureRate} — file-backed memory mappings
 *       also fail; NIO buffers, memory-mapped files, and class loading all see allocation failures
 *   <li>JVM: OutOfMemoryError injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       reproduces the error seen by the application when heap is exhausted, triggering circuit
 *       breakers and health indicator failures before the OOM kill
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Sustained memory leak leads to OOM kill; pod restarts; in-flight requests are lost; if the
 * circuit breaker (Resilience4j) does not open in time, upstream callers accumulate failed requests
 * and may cascade to their own thread pool exhaustion.
 *
 * <h2>Industry references</h2>
 *
 * <p>Sustained memory leak → OOM kill → pod restart with in-flight request loss → circuit breaker
 * opens is a documented failure mode for long-running Spring Boot services without heap size caps
 * or proper native memory accounting.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.MEMORY})
 * @IncidentChaosSpringMemoryCrisis(pressureRate = 0.8)
 * class SpringMemoryCrisisTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringMemoryCrisis.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.springboot.testpack.l3.composers.SpringMemoryCrisisComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosSpringMemoryCrisis {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Probability (0.0–1.0) that mmap (anonymous and file-backed) allocations return ENOMEM. */
  double pressureRate() default 0.7;

  /** Class name prefix used to match Spring methods for OutOfMemoryError injection. */
  String classPattern() default "org.springframework";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosSpringMemoryCrisis[] value();
  }
}
