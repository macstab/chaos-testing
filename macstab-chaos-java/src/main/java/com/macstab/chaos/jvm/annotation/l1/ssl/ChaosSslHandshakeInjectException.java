/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.ssl;

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
 * Throws the configured exception (default: {@link java.io.IOException}) inside
 * {@link javax.net.ssl.SSLEngine#beginHandshake() SSLEngine.beginHandshake()} before the JSSE TLS
 * state machine initialises — every TLS handshake attempt fails immediately, making all new TLS
 * connections impossible regardless of network conditions.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SSL} selector family with the
 * {@code injectException} effect applied to the {@code SSL_HANDSHAKE} operation. It intercepts
 * the JSSE handshake entry points ({@code SSLEngine.beginHandshake()} and
 * {@code SSLSocket.startHandshake()}) and throws before the TLS state machine initialises,
 * exercising all error-handling paths in application code that manages TLS connections. The
 * annotation is declared on the test class or method alongside a container annotation and is
 * active for the lifetime of the annotated scope.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code SSLEngine#beginHandshake()} and
 * {@code SSLSocket#startHandshake()}. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the JSSE handshake state is initialised and before any
 *       TLS bytes are sent or received.
 *   <li>The exception injection effect constructs and throws an instance of the configured
 *       exception class (default: {@code java.io.IOException} with the configured message).
 *   <li>The exception propagates to the caller of {@code beginHandshake} or
 *       {@code startHandshake} — typically the HTTP client's connection layer, which wraps it in
 *       a {@code SSLException} or {@code ConnectException}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every HTTPS request or TLS-protected socket write throws an exception at the handshake
 *       step — assert the exception type and that it surfaces to the caller rather than being
 *       swallowed by an internal retry.
 *   <li>The underlying TCP connection is established but the TLS handshake is never sent — a
 *       packet capture would show a TCP SYN/ACK with no subsequent {@code ClientHello}.
 *   <li>Connection pools that attempt TLS on new connections fail pool-expansion; assert that the
 *       pool's failure metric is incremented and that the pool does not grow beyond its initial
 *       size.
 *   <li>Circuit breakers that track TLS failures open after the configured threshold; assert the
 *       circuit is {@code OPEN} and subsequent calls fail fast without attempting a connection.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a certificate-rotation event where
 * the new TLS certificate is not yet trusted by the client's trust store — every
 * {@code SSLHandshakeException}: certificate_unknown causes the client's connection pool to drain,
 * circuit breakers to open, and the service to enter a degraded state until the trust store is
 * updated and the application is restarted.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent retransforms {@code SSLEngineImpl} and
 * {@code SSLSocketImpl} (both in the {@code sun.security.ssl} package, loaded by the bootstrap
 * class loader). The injected exception is thrown before the {@code Handshaker} object is
 * allocated and before any internal lock on the SSL engine is acquired — the engine is left in
 * the {@code NOT_HANDSHAKING} state with no internal state mutation.
 *
 * <p><strong>Exception type selection.</strong> {@code SSLEngine.beginHandshake()} and
 * {@code SSLSocket.startHandshake()} both declare {@code throws SSLException}. Configure
 * {@code exceptionClassName = "javax.net.ssl.SSLHandshakeException"} (a subclass of
 * {@code SSLException}) with a realistic message to mimic a certificate-validation failure, or
 * use {@code "javax.net.ssl.SSLException"} for a more generic TLS error. Callers that catch only
 * {@code SSLHandshakeException} and not the parent {@code SSLException} may miss the injected
 * exception if the configured type does not match.
 *
 * <p><strong>TLS session cache.</strong> JSSE maintains a {@code SSLSessionContext} that caches
 * negotiated sessions for resumption. The inject-exception effect fires before any session lookup
 * or resumption attempt — even connections that would have used a cached session fail immediately.
 * This simulates scenarios where the session cache is invalidated (e.g. after a server restart)
 * and every connection requires a full handshake that then fails.
 *
 * <p><strong>Distinction from {@code ChaosSslHandshakeDelay}.</strong> The delay effect lets the
 * handshake complete (with added latency); the TLS connection is eventually usable. The
 * inject-exception effect prevents the handshake from starting; the TLS connection is never
 * established. Use inject-exception to test circuit breakers, retry limits, and fallback-to-HTTP
 * logic; use delay to test handshake-timeout configuration.
 *
 * <p><strong>Interaction with HTTP/2 and multiplexed connections.</strong> HTTP/2 clients
 * multiplex many requests over a single TLS connection. If that connection's initial handshake
 * fails due to this annotation, the entire HTTP/2 session fails — all in-flight requests on that
 * connection receive the exception, multiplying the fault's impact across concurrent callers.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSslHandshakeInjectException(
 *     exceptionClassName = "javax.net.ssl.SSLHandshakeException",
 *     message = "chaos: certificate_unknown")
 * class SslHandshakeFailureTest {
 *
 *   @Test
 *   void circuitBreakerOpensAfterTlsFailures(AppConnectionInfo info) {
 *     for (int i = 0; i < 5; i++) {
 *       assertThatThrownBy(() -> client.httpsGet(info, "https://downstream/api"))
 *           .isInstanceOf(SSLException.class);
 *     }
 *     assertThat(client.circuitBreakerState(info)).isEqualTo("OPEN");
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#SSL_HANDSHAKE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#ssl(java.util.Set)
 * @see ChaosSslHandshakeDelay
 */
@Repeatable(ChaosSslHandshakeInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SSL,
    operationType = OperationType.SSL_HANDSHAKE)
public @interface ChaosSslHandshakeInjectException {

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
   * @ChaosSslHandshakeInjectException(id = "primary",  probability = 0.001)
   * @ChaosSslHandshakeInjectException(id = "replica",  probability = 0.01)
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
    ChaosSslHandshakeInjectException[] value();
  }
}
