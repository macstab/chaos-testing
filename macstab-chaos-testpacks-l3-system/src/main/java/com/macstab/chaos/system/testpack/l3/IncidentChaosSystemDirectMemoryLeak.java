/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates Netty/gRPC off-heap ByteBuffer exhaustion: direct memory is allocated without a
 * {@code Cleaner} reference, preventing GC reclamation. The heap remains completely clean.
 * The pod is alive to liveness probes. Every new NIO, Netty, or gRPC request fails.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>JVM: DirectBufferPressure allocating {@code totalMb} MB in {@code bufferSizeMb} MB
 *       chunks, retained without a Cleaner (leak mode)
 *   <li>JVM: {@code OutOfMemoryError("Direct buffer memory")} injection on {@code io.netty}
 *       classes at METHOD_EXIT
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Severe</strong><br>Heap is clean; liveness probes pass; every new
 * NIO/Netty/gRPC allocation fails with {@code OutOfMemoryError: Direct buffer memory}.
 * Standard heap monitoring is completely blind to this failure mode.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosSystemDirectMemoryLeak(totalMb = 512, bufferSizeMb = 4)
 * class DirectMemoryLeakTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSystemDirectMemoryLeak.List.class)
@ChaosL3(composer = "com.macstab.chaos.system.testpack.l3.composers.SystemDirectMemoryLeakComposer", severity = Severity.SEVERE)
public @interface IncidentChaosSystemDirectMemoryLeak {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Total direct memory to allocate and retain in megabytes. */
    int totalMb() default 256;

    /** Size of each individual direct buffer allocation in megabytes. */
    int bufferSizeMb() default 1;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosSystemDirectMemoryLeak[] value();
    }
}
