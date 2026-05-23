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
 * Intercepts {@code HttpClient.send()} and throws an arbitrary configurable exception class before
 * any network activity occurs, allowing tests to verify handling of specific exception types that
 * the JDK HTTP client or wrapping libraries may surface.
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
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()}, then throws it.
 *   <li>No network activity occurs; the exception propagates to the caller immediately.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every {@code HttpClient.send()} call throws the configured exception; assert that the
 *       application's exception handler for that specific type is invoked correctly.
 *   <li>Use {@code java.net.http.HttpConnectTimeoutException} to test connect-timeout handlers
 *       separately from {@code java.io.IOException} general-failure handlers.
 *   <li>Framework-level error translation: Spring's {@code RestTemplate} wraps most HTTP client
 *       exceptions in {@code ResourceAccessException}; assert that translation is correct.
 *   <li><strong>Production failure mode:</strong> DNS resolution failure surfaces as
 *       {@code java.net.UnknownHostException}; inject that to verify the application returns a
 *       meaningful user-facing error rather than a raw stack trace.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The exception injection translator uses {@code Class.forName(exceptionClassName)} on the
 * agent's class loader, then instantiates the exception via the single-argument
 * {@code (String message)} constructor. If the class is not found or lacks that constructor, the
 * agent falls back to wrapping a {@code RuntimeException} and logs a warning — the fault still
 * fires, but with a different type than requested. Always verify the injected type at test
 * authoring time.
 *
 * <p>The interception point is the same {@code jdk.internal.net.http.HttpClientImpl#send} entry
 * used by all HTTP_CLIENT_SEND rules. Because the throw happens before any socket work, the
 * connection pool is untouched and no file descriptors are consumed. The exception is thrown
 * without wrapping, so if the configured class is a checked exception that the caller does not
 * catch, the JVM will propagate it as an undeclared checked exception (technically legal at
 * bytecode level when thrown through Byte Buddy's intercept mechanism).
 *
 * <p>The difference between this annotation and {@link ChaosHttpClientSendReject} is precision:
 * {@code Reject} always uses {@code IOException} (matching the JDK's declared throws), while
 * {@code InjectException} accepts any class including {@code HttpTimeoutException},
 * {@code SSLHandshakeException}, or application-specific unchecked exceptions.
 *
 * <p>For testing SSL handshake failures specifically, inject
 * {@code javax.net.ssl.SSLHandshakeException}; this exercises the branch in application code that
 * handles certificate errors, which is distinct from the branch that handles connection refused.
 *
 * <p>When combined with method-scope, you can inject the fault for a single {@code @Test} and
 * verify that exactly one retry is attempted by counting mock invocations before and after the
 * fault window.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosHttpClientSendInjectException(
 *     exceptionClassName = "java.net.http.HttpConnectTimeoutException",
 *     message = "synthetic connect timeout")
 * class ConnectTimeoutHandlingTest {
 *   @Test
 *   void applicationLogsTimeoutAndReturns504(ConnectionInfo info) {
 *     // assert HTTP 504 response and timeout metric incremented
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
 * @see ChaosHttpClientSendReject
 * @see ChaosHttpClientSendAsyncInjectException
 */
@Repeatable(ChaosHttpClientSendInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.HTTP_CLIENT,
    operationType = OperationType.HTTP_CLIENT_SEND)
public @interface ChaosHttpClientSendInjectException {

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
   * @ChaosHttpClientSendInjectException(id = "primary",  probability = 0.001)
   * @ChaosHttpClientSendInjectException(id = "replica",  probability = 0.01)
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
    ChaosHttpClientSendInjectException[] value();
  }
}
