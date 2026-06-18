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
 * <p>Simulates a gRPC server connection drain during a rolling deploy: servers send GOAWAY frames
 * which manifest as connection resets, while a drain-lag delay on SEND operations models the
 * graceful-drain window where in-flight RPCs must complete before the connection is closed.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: CONNECT → ECONNRESET at {@code toxicity} — GOAWAY frames cause new connection
 *       attempts to be reset as the server drains and stops accepting new streams
 *   <li>Connection: SEND latency of 50 ms at {@code toxicity} — drain-lag delay on SEND operations
 *       models the graceful-drain window where in-flight RPCs are completing slowly
 *   <li>JVM: {@code injectException("io.grpc.StatusRuntimeException", "UNAVAILABLE: server draining
 *       connection")} on classes matching {@code classPattern} at METHOD_ENTER — surfaces drain
 *       state to the application layer before the network reset fires
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Clients must detect the GOAWAY and retry on a new connection; if retries are not implemented,
 * requests fail during the entire drain window; channel warm-up latency on new connections can
 * extend the effective unavailability.
 *
 * <h2>Industry references</h2>
 *
 * <p>Rolling deploy server GOAWAY drain is described in the gRPC documentation §"Server Reflection"
 * and §"Graceful Server Shutdown", the Envoy documentation §"Draining", and post-mortems from
 * Kubernetes rolling update incidents where clients did not handle GOAWAY correctly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosGrpcConnectionDrain(toxicity = 0.7)
 * class GrpcConnectionDrainTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosGrpcConnectionDrain.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.grpc.testpack.l3.composers.GrpcConnectionDrainComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosGrpcConnectionDrain {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT and SEND syscalls subjected to drain faults (0.0–1.0). */
  double toxicity() default 0.6;

  /** Class name prefix used to match gRPC client methods for exception injection. */
  String classPattern() default "io.grpc";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosGrpcConnectionDrain[] value();
  }
}
