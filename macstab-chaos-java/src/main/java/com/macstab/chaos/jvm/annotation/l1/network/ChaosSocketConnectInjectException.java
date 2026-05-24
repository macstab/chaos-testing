/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.network;

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
 * Intercepts {@code Socket.connect()} and throws the configured exception before any TCP handshake
 * occurs, simulating a connection failure with a specific exception type such as {@code
 * ConnectException}, {@code SocketTimeoutException}, or a custom application-level error at the
 * blocking socket layer used by JDBC drivers and legacy HTTP clients.
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
 *   <li>Before every call to {@code java.net.Socket#connect(SocketAddress)} or {@code
 *       Socket#connect(SocketAddress, int)} inside the target container's JVM, the chaos agent
 *       intercepts the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no SYN packet is sent and the socket
 *       remains in its pre-connect unbound state.
 *   <li>The exception propagates to the caller — whether a JDBC driver, Apache HttpClient, or
 *       direct socket user — as if the OS returned an error from the {@code connect(2)} syscall.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>JDBC drivers catch {@code IOException} from the socket and wrap it in {@code SQLException}
 *       (often {@code SQLRecoverableException} or vendor-specific subclasses); connection pools
 *       (HikariCP, c3p0) receive the exception from the driver's {@code connect()} method and
 *       increment their connection-failure counter; assert that the pool's health metrics reflect
 *       the failure.
 *   <li>Inject {@code java.net.ConnectException} to simulate connection refused; inject {@code
 *       java.net.SocketTimeoutException} to simulate a connect timeout; each exception type
 *       exercises a different branch in the driver's retry and reconnect logic.
 *   <li>Apache HttpClient 4.x's {@code DefaultHttpRequestRetryHandler} retries on {@code
 *       IOException}; assert that the configured number of retries fires and that the retry
 *       interval respects any backoff strategy.
 *   <li><strong>Production failure mode:</strong> a host's iptables rule is mistakenly deleted,
 *       causing the application to reach the database over a path that returns TCP RST; the JDBC
 *       driver sees {@code ConnectException: Connection refused} and wraps it in a {@code
 *       SQLRecoverableException}; HikariCP's connection validation fails; the pool is drained of
 *       all connections simultaneously and begins a reconnect storm against the database,
 *       compounding the original misconfiguration with heavy connection churn.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.Socket#connect(SocketAddress, int)} via Byte Buddy.
 * Unlike {@link ChaosSocketConnectReject} which always throws {@code IOException} with no
 * customisation, this annotation allows injecting any exception class — including vendor-specific
 * {@code SQLExceptions} or framework-specific types — enabling fine-grained testing of exception
 * classification logic.
 *
 * <p>The PostgreSQL JDBC driver's {@code org.postgresql.core.v3.ConnectionFactoryImpl} opens a
 * plain {@code Socket} and calls {@code connect(addr, loginTimeout * 1000)}. The injected exception
 * propagates up through the driver as {@code PSQLException} wrapping the original {@code
 * IOException}; Spring's {@code SQLExceptionTranslator} maps it to a {@code
 * DataAccessResourceFailureException}. Tests should assert this entire translation chain, not just
 * the raw exception.
 *
 * <p>MySQL Connector/J ({@code com.mysql.cj.protocol.StandardSocketFactory}) uses the same {@code
 * Socket.connect()} path. When the exception propagates, the connector re-throws it as a {@code
 * CJCommunicationsException} wrapping the {@code IOException}; Spring maps this to {@code
 * TransientDataAccessResourceException} if the SQL state is {@code 08S01}.
 *
 * <p>The distinction between this annotation and {@link ChaosSocketConnectReject} is exception type
 * specificity: the reject effect always throws a generic {@code IOException}; this effect throws
 * whatever class is configured, enabling injection of subtypes that trigger specific retry or
 * circuit-breaker logic that only activates for certain exception hierarchies.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketConnectInjectException(
 *     exceptionClassName = "java.net.SocketTimeoutException",
 *     message = "connect timed out after 30000ms")
 * class JdbcConnectTimeoutTest {
 *   @Test
 *   void hikariCpRetriesAndAlertsFire(ConnectionInfo info) {
 *     // assert HikariCP connection-timeout metric increments and pool health degrades
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the translator
 *       class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSocketConnectReject
 * @see ChaosSocketConnectDelay
 */
@Repeatable(ChaosSocketConnectInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_CONNECT)
public @interface ChaosSocketConnectInjectException {

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
   * @ChaosSocketConnectInjectException(id = "primary",  probability = 0.001)
   * @ChaosSocketConnectInjectException(id = "replica",  probability = 0.01)
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
    ChaosSocketConnectInjectException[] value();
  }
}
