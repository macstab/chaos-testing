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
 * Intercepts {@code Socket.connect()} and throws {@code java.io.IOException} immediately before any
 * TCP handshake occurs, simulating a hard connection-refused failure at the blocking socket layer
 * used by JDBC drivers, legacy HTTP clients, and any code that uses {@code java.net.Socket} for
 * outbound connections.
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
 *   <li>The agent throws {@code java.io.IOException} immediately; no SYN packet is sent and the
 *       socket remains unconnected; the file descriptor is not modified.
 *   <li>The exception propagates directly to the caller — JDBC driver, HTTP client, or raw socket
 *       user — which must handle it as a connection-establishment failure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>JDBC connection pools receive the exception from the driver's socket initialisation and
 *       mark the connection attempt as failed; HikariCP increments its failed-connection counter
 *       and may invoke the configured {@code ExceptionOverrideClassName} to decide whether to
 *       retry; assert that the pool's minimum-idle guarantee causes repeated reconnect attempts.
 *   <li>The Apache HttpClient 4.x {@code DefaultHttpClient} retries on {@code IOException} up to
 *       the configured retry count; assert that retries fire and that request headers are not
 *       resent on non-idempotent requests.
 *   <li>Applications using Spring's {@code RestTemplate} backed by Apache HttpClient receive a
 *       {@code ResourceAccessException} wrapping the {@code IOException}; assert that the
 *       application's error handler translates this correctly for the API caller.
 *   <li><strong>Production failure mode:</strong> a Kubernetes NetworkPolicy is temporarily
 *       misconfigured, blocking outbound traffic from the application to its Kafka broker; the
 *       Kafka producer's blocking-socket connect throws {@code ConnectException}; the producer
 *       retries according to its {@code reconnect.backoff.ms} setting; during the retry window the
 *       producer's send buffer fills; once the NetworkPolicy is fixed the producer bursts all
 *       buffered records, causing a throughput spike that exhausts broker capacity.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.Socket#connect(SocketAddress, int)}. Unlike the NIO
 * variant ({@link ChaosNioChannelConnectReject}), which targets {@code SocketChannel.connect()},
 * this annotation targets the blocking {@code Socket} API used by traditional I/O frameworks. The
 * distinction matters: Kafka's Java producer, Zookeeper client, and most JDBC drivers use blocking
 * sockets; Netty and modern reactive clients use NIO channels.
 *
 * <p>When {@code Socket.connect()} throws, the socket's state remains {@code Socket.isConnected()
 * == false}; the socket is not closed; callers may attempt to call {@code connect()} again on the
 * same socket (though most frameworks create a new socket for each attempt). The file descriptor
 * allocated by {@code Socket()} is not released by a failed connect; callers that do not close the
 * socket after a failed connect leak a file descriptor. This annotation exercises those paths.
 *
 * <p>JDBC driver behaviour: PostgreSQL JDBC catches the {@code IOException} in {@code
 * org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl()} and wraps it in {@code
 * PSQLException} with SQL state {@code 08001} (connection exception). MySQL Connector/J wraps it in
 * {@code CJCommunicationsException} with SQL state {@code 08S01}. HikariCP calls {@code
 * isNetworkTimeout()} to determine if the exception is transient; for {@code IOException} it does
 * not evict the pool entry immediately but marks the connection for validation.
 *
 * <p>The reject effect is faster to observe than a delay: instead of waiting for a timeout, the
 * test sees an immediate exception. This makes it suitable for testing fast-fail code paths —
 * circuit breakers, fallback logic — where a long delay would mask the failure as a timeout rather
 * than a rejection.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketConnectReject(message = "connection refused by chaos")
 * class JdbcPoolDrainTest {
 *   @Test
 *   void poolDrainsAndReconnectStormIsObserved(ConnectionInfo info) {
 *     // assert HikariCP pool size drops to zero and reconnect rate metric spikes
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
 * @see ChaosSocketConnectDelay
 * @see ChaosSocketConnectInjectException
 * @see ChaosNioChannelConnectReject
 */
@Repeatable(ChaosSocketConnectReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_CONNECT)
public @interface ChaosSocketConnectReject {

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
   * @ChaosSocketConnectReject(id = "primary",  probability = 0.001)
   * @ChaosSocketConnectReject(id = "replica",  probability = 0.01)
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
    ChaosSocketConnectReject[] value();
  }
}
