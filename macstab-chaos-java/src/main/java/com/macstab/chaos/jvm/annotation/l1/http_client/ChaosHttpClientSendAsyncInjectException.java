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
 * Intercepts {@code HttpClient.sendAsync()} and throws the configured exception class synchronously
 * before the {@code CompletableFuture} is returned, enabling precise testing of error-handling code
 * that distinguishes between specific async-HTTP exception types.
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
 *   <li>Before every call to {@code java.net.http.HttpClient#sendAsync(HttpRequest,
 *       HttpResponse.BodyHandler)} inside the target container's JVM, the chaos agent intercepts
 *       the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it synchronously.
 *   <li>No future is returned, no network activity occurs, and no thread-pool task is submitted to
 *       the HTTP client's internal executor.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every {@code sendAsync()} call throws the specified exception synchronously; assert that
 *       the application's per-exception-type handler is invoked for that exact class.
 *   <li>Inject {@code java.net.http.HttpConnectTimeoutException} to test the connect-timeout code
 *       path in reactive pipelines that handle it differently from a general {@code IOException}.
 *   <li>Inject {@code javax.net.ssl.SSLHandshakeException} to verify the application logs a
 *       certificate-related alert and does not silently swallow TLS errors.
 *   <li><strong>Production failure mode:</strong> when a service mesh rotates mTLS certificates,
 *       {@code SSLHandshakeException} fires on every async call; an application without a specific
 *       handler logs generic "Unknown error" and does not trigger the certificate-rotation alert.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The exception injection translator reflectively loads the class named by
 * {@link #exceptionClassName()} using the agent's class loader, which has access to all classes
 * visible to the bootstrap and system loaders. Application-specific exception classes that are not
 * on the bootstrap path may not be resolvable; use standard JDK or framework exception types for
 * reliable injection. The translator attempts the single-argument {@code String} constructor first,
 * then falls back to the no-argument constructor, then wraps in a {@code RuntimeException}.
 *
 * <p>The interception point is the same {@code jdk.internal.net.http.HttpClientImpl#sendAsync}
 * entry used by all HTTP_CLIENT_SEND_ASYNC rules. Because the throw precedes any executor
 * submission, the JDK's internal worker threads ({@code HttpClientImpl.SelectorManager} and
 * the response handler threads) are not involved. From the application's perspective, the call
 * never left the calling thread.
 *
 * <p>This annotation is the async counterpart of {@link ChaosHttpClientSendInjectException}. The
 * distinction matters for applications that use both synchronous and asynchronous call styles:
 * both rules must be registered to cover all HTTP call sites.
 *
 * <p>Combining this annotation with {@link ChaosHttpClientSendAsyncReject} in different test
 * methods provides coverage of both the "canonical IOException" path (Reject) and the
 * "specific exception subtype" path (InjectException) within the same application, enabling
 * branch coverage of exception-type dispatch logic.
 *
 * <p>Unlike {@link ChaosHttpClientSendAsyncDelay}, which tests latency handling, this annotation
 * tests error routing: the test verifies which branch of a {@code try / catch} or
 * {@code .exceptionally()} handler fires for each exception type.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosHttpClientSendAsyncInjectException(
 *     exceptionClassName = "javax.net.ssl.SSLHandshakeException",
 *     message = "certificate expired")
 * class TlsHandshakeFailureTest {
 *   @Test
 *   void applicationAlertsCertificateTeamAndReturns503(ConnectionInfo info) {
 *     // assert TLS-failure metric is incremented and HTTP 503 is returned
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
 * @see ChaosHttpClientSendAsyncReject
 * @see ChaosHttpClientSendInjectException
 */
@Repeatable(ChaosHttpClientSendAsyncInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.HTTP_CLIENT,
    operationType = OperationType.HTTP_CLIENT_SEND_ASYNC)
public @interface ChaosHttpClientSendAsyncInjectException {

  /**
   * @return binary class name of the exception to throw (e.g. "java.io.IOException")
   */
  String exceptionClassName() default "java.io.IOException";

  /**
   * @return exception message
   */
  String message() default "injected by chaos L1";

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
   * @ChaosHttpClientSendAsyncInjectException(id = "primary",  probability = 0.001)
   * @ChaosHttpClientSendAsyncInjectException(id = "replica",  probability = 0.01)
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
    ChaosHttpClientSendAsyncInjectException[] value();
  }
}
