/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.http.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a timeout cascade across a service call chain: each hop exhausts its deadline budget
 * waiting for the next, so a single slow upstream propagates full timeout failures through every
 * caller in the chain.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: TCP timeout of {@code latencyMs} ms on all endpoints — the primary socket-level
 *       delay that burns the request timeout budget
 *   <li>DNS: forward lookup latency of {@code latencyMs / 2} ms — pre-connection DNS delay further
 *       reduces the effective timeout margin available for actual request processing
 *   <li>JVM: SocketTimeoutException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       reproduces the application-level exception thrown when the combined latency exceeds the
 *       configured HTTP client timeout
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Every service in the call chain fails with timeouts; thread pools saturate with blocked request
 * threads; the outage blast radius spans the entire downstream dependency graph.
 *
 * <h2>Industry references</h2>
 *
 * <p>Timeout cascade across service chains is described in Netflix's Hystrix post-mortem literature
 * and the AWS re:Invent sessions on microservice resilience — each hop's timeout is shorter than
 * the sum of downstream timeouts, making cascades structurally inevitable without bulkheads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
 * @IncidentChaosHttpCascadingTimeout(latencyMs = 5000L)
 * class HttpCascadeTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosHttpCascadingTimeout.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.http.testpack.l3.composers.HttpCascadingTimeoutComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosHttpCascadingTimeout {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Milliseconds applied as TCP timeout; DNS latency is set to half this value. */
  long latencyMs() default 3000L;

  /** Class name prefix used to match HTTP client methods for timeout exception injection. */
  String classPattern() default "http";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosHttpCascadingTimeout[] value();
  }
}
