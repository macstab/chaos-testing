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
 * <p>Simulates a gRPC DNS-based load-balancing failure: Kubernetes service DNS flap and SRV record
 * resolution lag cause the name resolver to retry-storm the DNS server while new connection
 * attempts are simultaneously refused by backends that are not yet reachable.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>DNS: EAI_AGAIN on every forward lookup — DNS name resolver retry storm; every {@code
 *       getaddrinfo()} call fails transiently, forcing the gRPC resolver to retry
 *   <li>DNS: getaddrinfo latency of 500 ms on every forward lookup — slow DNS resolution delays
 *       each retry, extending the window during which no backends are reachable
 *   <li>Connection: CONNECT → ECONNREFUSED at {@code toxicity} — backends refuse connections during
 *       the period when the DNS-based LB is converging to new backend addresses
 *   <li>JVM: {@code injectException("io.grpc.StatusRuntimeException", "UNAVAILABLE: load balancer
 *       name resolution failure")} on classes matching {@code classPattern} at METHOD_ENTER —
 *       surfaces LB failure to the application layer before the network call is attempted
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * All backends become temporarily unreachable; the gRPC channel enters TRANSIENT_FAILURE;
 * client-side load balancing stalls until DNS resolves and new connections are established; the
 * warm-up latency of new channels extends the unavailability.
 *
 * <h2>Industry references</h2>
 *
 * <p>DNS-based gRPC LB failure is documented in the gRPC documentation §"Load Balancing in gRPC",
 * the Kubernetes documentation §"DNS for Services and Pods", and post-mortems from Kubernetes
 * deployments where headless service SRV record churn during rolling updates caused extended
 * TRANSIENT_FAILURE periods for gRPC clients using the DNS resolver.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.DNS, LibchaosLib.NET})
 * @IncidentChaosGrpcLoadBalancingFailure(toxicity = 0.6)
 * class GrpcLoadBalancingFailureTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosGrpcLoadBalancingFailure.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.grpc.testpack.l3.composers.GrpcLoadBalancingFailureComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosGrpcLoadBalancingFailure {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
  double toxicity() default 0.5;

  /** Class name prefix used to match gRPC client methods for exception injection. */
  String classPattern() default "io.grpc";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosGrpcLoadBalancingFailure[] value();
  }
}
