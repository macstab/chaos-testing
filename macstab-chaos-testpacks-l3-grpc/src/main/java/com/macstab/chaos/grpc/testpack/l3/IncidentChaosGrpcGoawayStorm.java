/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.grpc.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a gRPC GOAWAY storm caused by {@code maxConnectionAge} cycling: the server
 * continuously sends GOAWAY frames to drain connections, which manifest as ECONNRESET on in-flight
 * RECV operations. Combined with JVM StatusRuntimeException(UNAVAILABLE) injection this tests
 * client handling of the steady error rate seen in practice.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV → ECONNRESET at {@code toxicity} — GOAWAY closes in-flight streams; active
 *       RPCs are aborted mid-response
 *   <li>JVM: StatusRuntimeException(UNAVAILABLE) on class prefix {@code classPattern} at
 *       METHOD_EXIT — surfaces GOAWAY-caused UNAVAILABLE at the gRPC stub layer, reproducing the
 *       10–20 errors/hour profile reported in grpc-java #9566
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Steady 10–20 UNAVAILABLE errors/hour; no transparent retry is possible for non-idempotent RPCs;
 * load balancers may react to the error rate and remove the endpoint from the pool, causing traffic
 * redistribution spikes.
 *
 * <h2>Industry references</h2>
 *
 * <p>maxConnectionAge-triggered GOAWAY storms are described in grpc-java issue #9566 and Envoy
 * documentation §"Connection Management". Clients that do not implement transparent retry on GOAWAY
 * see a continuous background rate of UNAVAILABLE errors proportional to their RPC rate and the
 * configured maxConnectionAge.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosGrpcGoawayStorm(toxicity = 0.6, classPattern = "io.grpc")
 * class GrpcGoawayStormTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosGrpcGoawayStorm.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.grpc.testpack.l3.composers.GrpcGoawayStormComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosGrpcGoawayStorm {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of RECV syscalls that return ECONNRESET (0.0–1.0). */
  double toxicity() default 0.6;

  /** Class name prefix used to match gRPC stub methods for exception injection. */
  String classPattern() default "io.grpc";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosGrpcGoawayStorm[] value();
  }
}
