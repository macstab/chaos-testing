/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a G1 GC "to-space exhausted" evacuation failure under heap pressure: the heap is
 * pre-filled to near capacity while a sustained allocation rate prevents G1 from completing normal
 * evacuation, forcing a full stop-the-world GC with 5–30x longer pause times. An OOM injection
 * ensures the application observes the failure.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: HeapPressure retaining {@code heapFillMb} MB of live objects in 1 MB chunks
 *   <li>JVM: GcPressure at {@code allocationRateMbPerSec} MB/s for 30 s cycles to keep G1 busy
 *   <li>JVM: OutOfMemoryError injection on {@code com.} classes at METHOD_EXIT — reproduces
 *       application-visible heap exhaustion
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * GC pause 5–30x longer than normal; liveness probes kill the pod mid-GC; rolling deploy stalls
 * with pods unable to start up cleanly.
 *
 * <h2>Industry references</h2>
 *
 * <p>G1 to-space exhausted: a documented G1 failure mode where insufficient survivor space during
 * mixed or young GC causes evacuation failure, triggering a full STW collection. Frequently
 * observed in large-heap services (>8 GB) under sustained write-heavy workloads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosJvmG1ToSpaceExhausted(heapFillMb = 512, allocationRateMbPerSec = 100L)
 * class G1ToSpaceExhaustedTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmG1ToSpaceExhausted.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmG1ToSpaceExhaustedComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosJvmG1ToSpaceExhausted {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Megabytes of heap to retain as live objects (fills old-gen). */
  int heapFillMb() default 256;

  /** Allocation rate in MB/s to sustain GC pressure. */
  long allocationRateMbPerSec() default 50L;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmG1ToSpaceExhausted[] value();
  }
}
