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
 * Intercepts {@code Socket.connect()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the TCP handshake begins, simulating slow connection establishment as seen by
 * blocking I/O clients using {@code java.net.Socket}, legacy JDBC drivers, and any framework that
 * issues blocking connect calls from dedicated I/O threads.
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
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code connect()} executes normally, blocking until the
 *       TCP three-way handshake completes or the SO_TIMEOUT fires.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The calling thread is blocked for at least {@link #delayMs()} ms before the SYN packet is
 *       sent; if the calling thread is a Tomcat acceptor thread or a connection-pool filler thread,
 *       assert that the pool's connection-creation rate drops and that the pool waits rather than
 *       spawning extra creation threads.
 *   <li>If the injected delay is longer than the {@code connect} timeout configured via {@code
 *       Socket.connect(addr, timeout)}, the timeout fires during the sleep (after the sleep the
 *       actual connect is attempted and may succeed, but it will be past the caller's deadline);
 *       assert that the caller receives a {@code SocketTimeoutException} as expected.
 *   <li>JDBC drivers that use blocking sockets to establish database connections (PostgreSQL JDBC,
 *       MySQL Connector/J) will take longer to return from {@code DataSource.getConnection()}
 *       during pool warm-up; assert that the application's startup probe tolerates the extended
 *       connection-establishment time.
 *   <li><strong>Production failure mode:</strong> a misconfigured DNS TTL causes connection
 *       attempts to land on a newly provisioned replica that is still initialising; the replica
 *       accepts TCP connections but delays them at the application level; blocking socket clients
 *       see elevated connect latency that manifests as increased connection-pool acquisition time
 *       across all threads, degrading the entire thread pool.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.Socket#connect(SocketAddress, int)} via Byte Buddy.
 * Unlike the NIO path where {@code SocketChannel.connect()} is called from an event loop, {@code
 * Socket.connect()} is always called from the thread that owns the socket — typically a dedicated
 * per-connection thread in thread-per-request servers (Tomcat BIO connector, legacy JDBC drivers,
 * Apache HttpClient 4.x's connection manager).
 *
 * <p>The connect timeout parameter passed to {@code Socket.connect(addr, timeout)} is enforced by
 * the JVM via a {@code SO_TIMEOUT} set before the underlying {@code connect(2)} syscall. The chaos
 * delay fires before this syscall; if the delay alone exceeds the timeout, the timeout fires at the
 * {@code connect()} call itself after the sleep completes, giving the appearance of a connect
 * timeout. If the delay is shorter than the timeout, the remaining timeout budget applies to the
 * actual TCP handshake; a slow server can still trigger the timeout.
 *
 * <p>JDBC drivers wrap their socket in a {@code java.net.Socket} or {@code
 * javax.net.ssl.SSLSocket}; the chaos delay fires at the socket level, before the JDBC protocol
 * handshake. The JDBC driver's login timeout (configured via {@code
 * DriverManager.setLoginTimeout()}) is measured from the {@code getConnection()} call; the chaos
 * delay is included in this measurement. If the delay exceeds the login timeout, the driver throws
 * {@code SQLTimeoutException} before any JDBC protocol bytes are exchanged.
 *
 * <p>Apache HttpClient 4.x uses blocking sockets via its {@code PlainConnectionSocketFactory} and
 * {@code SSLConnectionSocketFactory}; both delegate to {@code Socket.connect()}. HttpClient 5 and
 * async clients use NIO channels; for those, use {@link
 * com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelConnectDelay} instead.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketConnectDelay(delayMs = 3000)
 * class JdbcConnectionPoolWarmUpTest {
 *   @Test
 *   void startupProbeToleratesSlowConnectionEstablishment(ConnectionInfo info) {
 *     // assert application remains in STARTING state and probe does not kill it
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
 * @see ChaosSocketConnectInjectException
 * @see com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelConnectDelay
 */
@Repeatable(ChaosSocketConnectDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_CONNECT)
public @interface ChaosSocketConnectDelay {

  /**
   * @return min delay in milliseconds
   */
  long delayMs() default 100L;

  /**
   * @return max delay in milliseconds (defaults to delayMs for deterministic delay)
   */
  long maxDelayMs() default 100L;

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
   * @ChaosSocketConnectDelay(id = "primary",  probability = 0.001)
   * @ChaosSocketConnectDelay(id = "replica",  probability = 0.01)
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
    ChaosSocketConnectDelay[] value();
  }
}
