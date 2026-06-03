/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.grpc.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a gRPC deadline cascade: each hop in a service chain inherits a shorter deadline
 * budget from the caller. RECV latency delays response delivery while a negative monotonic clock
 * skew causes deadline checks to fire late, compounding the propagation failure across the call
 * chain.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: RECV latency of {@code latencyMs} ms at toxicity 1.0 — delays gRPC response
 *       frames, consuming the deadline budget before the response arrives
 *   <li>Time: MONOTONIC clock skew of {@code -skewMs} ms at probability 1.0 — slow monotonic
 *       makes deadline checks fire late, masking imminent expiry until the budget is fully consumed
 *   <li>JVM: {@code injectException("io.grpc.StatusRuntimeException", "DEADLINE_EXCEEDED: deadline
 *       propagation failure")} on classes matching {@code classPattern} at METHOD_ENTER — triggers
 *       DEADLINE_EXCEEDED at the Java layer before the network latency even fires
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>Deadline cascade means every service in the call chain
 * simultaneously receives DEADLINE_EXCEEDED; retry storms compound the latency; the entire fan-out
 * of the affected RPC path becomes unavailable until deadlines expire and retries are exhausted.
 *
 * <h2>Industry references</h2>
 * <p>gRPC deadline cascade is described in the gRPC documentation §"Deadlines", the Google SRE
 * book §"Handling Overload", and post-mortems from microservice deployments where a single slow
 * downstream hop caused cascading DEADLINE_EXCEEDED across an entire service mesh.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
 * @IncidentChaosGrpcDeadlinePropagation(latencyMs = 3000L, skewMs = 800L)
 * class GrpcDeadlinePropagationTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosGrpcDeadlinePropagation.List.class)
@ChaosL3(composer = "com.macstab.chaos.grpc.testpack.l3.composers.GrpcDeadlinePropagationComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosGrpcDeadlinePropagation {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Milliseconds of RECV latency injected into gRPC response frames. */
    long latencyMs() default 2000L;

    /** Milliseconds by which the monotonic clock is shifted backward (slow clock). */
    long skewMs() default 500L;

    /** Class name prefix used to match gRPC client methods for exception injection. */
    String classPattern() default "io.grpc";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosGrpcDeadlinePropagation[] value();
    }
}
