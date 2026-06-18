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
 * <p>Simulates a GCLocker-induced spurious OutOfMemoryError: monitor contention prevents threads
 * from releasing JNI critical sections, which blocks GC from running while sustained allocation
 * pressure triggers the GC overhead limit, causing {@code OutOfMemoryError: GC overhead limit
 * exceeded} even though the heap is not actually exhausted.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: GcPressure at {@code allocationRateMbPerSec} MB/s for 30 s cycles
 *   <li>JVM: MonitorContention with {@code contendingThreads} threads holding a synthetic lock for
 *       {@code lockHoldMs} ms — models JNI critical section contention that delays GC
 *   <li>JVM: OutOfMemoryError injection (GC overhead limit exceeded) on {@code com.} classes at
 *       METHOD_EXIT — reproduces the spurious OOM visible to the application
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Heap is not exhausted; restarting the pod fixes the issue immediately; the root cause (monitor
 * contention delaying GC) is never identified because the heap dump looks normal.
 *
 * <h2>Industry references</h2>
 *
 * <p>GCLocker-induced fake OOM: reported in the CleverTap 2021 incident where JNI library calls
 * holding critical sections blocked G1 GC, triggering GC overhead limit OOMs under normal heap
 * utilisation. The service restarted cleanly every time, masking the root cause.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosJvmGcLockerFakeOom(allocationRateMbPerSec = 150L, lockHoldMs = 75L, contendingThreads = 12)
 * class GcLockerFakeOomTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmGcLockerFakeOom.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmGcLockerFakeOomComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosJvmGcLockerFakeOom {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Allocation rate in MB/s to drive GC overhead pressure. */
  long allocationRateMbPerSec() default 100L;

  /** Duration in milliseconds each contending thread holds the synthetic lock. */
  long lockHoldMs() default 50L;

  /** Number of threads competing for the synthetic lock. */
  int contendingThreads() default 10;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmGcLockerFakeOom[] value();
  }
}
