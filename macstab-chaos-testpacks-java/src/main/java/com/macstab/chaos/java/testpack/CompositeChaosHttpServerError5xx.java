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
 * <p>Injects an {@code IOException} into {@code HttpClient.send()} with probability
 * {@link #probability()}, simulating a downstream service that responds with a 5xx error or
 * closes the connection before sending a response.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code java.net.http.HttpClient#send()} via the JVM chaos agent and throws an
 * {@code IOException} at the configured rate. In production, this pattern occurs when a downstream
 * microservice is overloaded and starts refusing or aborting connections, or when a load balancer
 * returns 503 to the client's HTTP layer.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Individual HTTP calls fail; callers that implement retry with backoff can recover. Callers
 * without retry will surface errors immediately. Services that aggregate results from multiple
 * downstreams may degrade gracefully if they handle partial failures.
 *
 * <h2>Industry references</h2>
 *
 * <p>HTTP 5xx error handling patterns are documented in RFC 7231 §6.6. Google SRE Book §22
 * "Addressing Cascading Failures" recommends exponential backoff with jitter for 5xx responses.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosHttpServerError5xx(probability = 0.5)
 * class HttpErrorTest {
 *   @Test
 *   void clientRetriesOnTransient5xxError(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosHttpServerError5xx.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.HttpServerError5xxComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosHttpServerError5xx {

  /**
   * Probability in {@code (0.0, 1.0]} that an {@code HttpClient.send()} call throws an exception.
   *
   * @return probability; default 0.5
   */
  double probability() default 0.5;

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
    CompositeChaosHttpServerError5xx[] value();
  }
}
