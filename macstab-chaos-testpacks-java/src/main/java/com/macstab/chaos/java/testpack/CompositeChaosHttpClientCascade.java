/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Delays every {@code HttpClient.send()} call by {@link #delayMs()} milliseconds, simulating a
 * slow downstream HTTP dependency and verifying that the application's HTTP client has appropriate
 * timeouts and does not hold its thread pool threads open indefinitely.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code java.net.http.HttpClient#send()} via the JVM chaos agent and injects a
 * deterministic delay. In production, slow downstreams arise from overloaded microservices,
 * saturated load balancers, or network congestion — the classic cascading-failure trigger where
 * a slow call backs up the calling service's thread pool until it exhausts.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Without a timeout shorter than {@code delayMs}, calling threads will block for the full delay.
 * Thread pools fill, incoming requests queue, and the service eventually becomes unresponsive.
 * This is the textbook cascading-failure pattern described by Nygard in "Release It!".
 *
 * <h2>Industry references</h2>
 *
 * <p>Michael Nygard, "Release It!" (2nd ed.) §4.4: "Cascade Failures". Netflix's Hystrix library
 * was created specifically to prevent this pattern by enforcing per-dependency timeouts and
 * circuit-breaker isolation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosHttpClientCascade(delayMs = 3000)
 * class HttpCascadeTest {
 *   @Test
 *   void circuitBreakerOpensOnSlowDownstream(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosHttpClientCascade.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.HttpClientCascadeComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosHttpClientCascade {

  /**
   * Delay injected before each {@code HttpClient.send()} returns, in milliseconds.
   *
   * @return delay in ms; default 3000
   */
  long delayMs() default 3_000L;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosHttpClientCascade[] value();
  }
}
