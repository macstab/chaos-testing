/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates the swap death spiral: memory pressure causes the heap to be swapped out;
 * a GC cycle must traverse the swapped pages, triggering a 45-second stop-the-world pause;
 * the liveness probe times out and kills the pod; swap clears on restart; the cycle repeats
 * approximately every 3 minutes.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>JVM: HeapPressure retaining {@code heapFillMb} MB (pushes heap to swap)
 *   <li>JVM: GcPressure at {@code allocationRateMbPerSec} MB/s (GC traverses swapped pages,
 *       causing a 45-second stop-the-world pause)
 *   <li>Filesystem: {@code READ} latency of 10 s on all paths (simulates swap page-fault
 *       latency on every file read)
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>Pattern repeats every 3 minutes; observed as
 * periodic pod restarts with no apparent cause. Root cause is swap — visible only via
 * {@code /proc/meminfo} (SwapUsed) and GC logs (pause times).
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosSystemSwapDeathSpiral(heapFillMb = 512, allocationRateMbPerSec = 200L)
 * class SwapDeathSpiralTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSystemSwapDeathSpiral.List.class)
@ChaosL3(composer = "com.macstab.chaos.system.testpack.l3.composers.SystemSwapDeathSpiralComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosSystemSwapDeathSpiral {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Amount of heap to retain in megabytes, pushing the JVM toward swap. */
    int heapFillMb() default 256;

    /** GC allocation rate in megabytes per second, forcing GC traversal of swapped pages. */
    long allocationRateMbPerSec() default 100L;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosSystemSwapDeathSpiral[] value();
    }
}
