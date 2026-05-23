/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.http_client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Intercepts {@code HttpClient.send()} and immediately throws {@code java.io.IOException} before
 * any network activity occurs, simulating a hard connection refusal on every synchronous HTTP
 * request in the target JVM.
 *
 * <h2>What this annotation is</h2>
 *
 * A JVM agent L1 chaos primitive — one typed annotation per (selector family, operation type,
 * effect) tuple. It is declared on a test class or method alongside a container annotation and
 * activates for the lifetime of the test class ({@code beforeAll} / {@code afterAll}) or a single
 * test method ({@code beforeEach} / {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>Before every call to {@code java.net.http.HttpClient#send(HttpRequest,
 *       HttpResponse.BodyHandler)} inside the target container's JVM, the chaos agent intercepts
 *       the thread.
 *   <li>The agent throws {@code java.io.IOException} (wrapping the configured {@link #message()})
 *       immediately — no TCP handshake, no DNS lookup, no bytes are exchanged.
 *   <li>The exception propagates to the caller exactly as a real network refusal would; the
 *       underlying {@code send()} never executes.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every {@code HttpClient.send()} call throws {@code IOException}; assert that the
 *       application catches it and converts it to the appropriate HTTP error response (e.g. 503).
 *   <li>Retry logic is exercised: frameworks that retry on {@code IOException} (Spring Retry,
 *       Resilience4j) will fire retries; assert that retry count and back-off are bounded.
 *   <li>Circuit breakers that open on {@code IOException} will trip; assert that the open state
 *       is correctly propagated to callers (fallback response, metric increment).
 *   <li><strong>Production failure mode:</strong> a downstream service refuses connections
 *       (e.g. firewall rule tightened at 02:00); the application's circuit breaker opens, the
 *       fallback path activates, but if the fallback also calls the same service the error
 *       cascades — an incident begins.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The reject effect intercepts {@code jdk.internal.net.http.HttpClientImpl#send} via Byte
 * Buddy before the method creates or acquires a connection. Because the throw happens before
 * any socket work, the JDK's internal connection pool is untouched: no connections are borrowed,
 * no half-open TCP sockets are left behind, and no file descriptors are leaked. This makes the
 * fault perfectly idempotent and safe to fire repeatedly in the same JVM process.
 *
 * <p>The thrown exception type is fixed to {@code java.io.IOException} to match what the JDK
 * HttpClient itself declares in its throws clause. Callers that catch {@code IOException} will
 * handle the injected fault exactly as they would handle a real network error. If the application
 * wraps the IOException in a framework-specific exception (e.g. Spring's
 * {@code ResourceAccessException}), assert against that wrapper type in tests.
 *
 * <p>The difference between {@code Reject} and {@link ChaosHttpClientSendInjectException} is
 * intent: reject uses the operation's canonical exception type (always {@code IOException} for
 * HTTP send) while inject-exception lets the test author specify an arbitrary class, useful for
 * testing handlers that distinguish between timeout exceptions and connection-refused exceptions.
 *
 * <p>When combined with a probability modifier the fault fires on a fraction of calls, which is
 * effective for testing partial-failure scenarios: some requests succeed, some fail, and the
 * application must produce consistent user-visible behaviour across both outcomes.
 *
 * <p>Unlike network-level faults (iptables drop), this fault is immediate — the kernel never sees
 * the connection attempt. This means connect-timeout logic in the application is not exercised;
 * use {@link ChaosHttpClientSendDelay} with a very long delay to test that path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosHttpClientSendReject(message = "downstream refused")
 * class DownstreamRefusedTest {
 *   @Test
 *   void applicationReturnsFallback(ConnectionInfo info) {
 *     // assert HTTP 503 with a fallback body is returned
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the
 *       translator class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosHttpClientSendInjectException
 * @see ChaosHttpClientSendDelay
 * @see ChaosHttpClientSendAsyncReject
 */
@Repeatable(ChaosHttpClientSendReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.HTTP_CLIENT,
    operationType = OperationType.HTTP_CLIENT_SEND)
public @interface ChaosHttpClientSendReject {

  /**
   * @return exception message used by the reject effect
   */
  String message() default "rejected by chaos L1";

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosHttpClientSendReject(id = "primary",  probability = 0.001)
   * @ChaosHttpClientSendReject(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosHttpClientSendReject[] value();
  }
}
