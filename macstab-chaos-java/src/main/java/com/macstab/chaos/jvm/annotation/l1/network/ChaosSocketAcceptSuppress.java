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
 * Intercepts {@code ServerSocket.accept()} and returns {@code null} without dequeuing any
 * connection from the kernel backlog, causing a blocking-socket server (Tomcat BIO, embedded
 * Zookeeper) to act as if no clients are connecting even while the TCP backlog fills up.
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
 *   <li>Before every call to {@code java.net.ServerSocket#accept()} inside the target container's
 *       JVM, the chaos agent intercepts the calling thread.
 *   <li>The agent returns {@code null} immediately without invoking the real {@code accept()}
 *       syscall; no connection is dequeued from the kernel accept queue.
 *   <li>The caller receives {@code null} as if {@code accept()} returned with no connection; most
 *       blocking-server loops check for {@code null} and continue looping.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The kernel backlog fills as completed TCP handshakes accumulate; once full, new connection
 *       attempts are rejected by the kernel; assert that clients receive connection refused or
 *       connection timeout errors depending on kernel configuration.
 *   <li>The acceptor thread spins returning {@code null} repeatedly without blocking; unlike the
 *       delay variant, the suppress variant does not block the thread — it keeps it busy; assert
 *       that the acceptor thread's CPU usage increases and that the application's monitoring
 *       detects a hot-spinning thread.
 *   <li>Existing established connections held by previously accepted threads are not affected; only
 *       new connection acceptance is suppressed; assert that in-flight requests continue to
 *       completion while no new connections are established.
 *   <li><strong>Production failure mode:</strong> a Tomcat BIO acceptor thread deadlocks inside the
 *       server socket factory (e.g. due to a custom SSL factory bug); the acceptor calls {@code
 *       accept()} but never returns; from the OS perspective, the listen socket is still open;
 *       clients complete TCP handshakes and are placed in the backlog; once the backlog fills the
 *       OS drops SYN packets; health-check systems that use new TCP connections to check
 *       application health report the service as down, but existing connections continue to work —
 *       a misleading dual-state failure.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.ServerSocket#accept()}. In contrast to {@code
 * ServerSocketChannel.accept()} (NIO) which legitimately returns {@code null} in non-blocking mode,
 * {@code ServerSocket.accept()} in blocking mode never returns {@code null} in normal operation —
 * it either blocks until a connection arrives or throws {@code SocketTimeoutException} if {@code
 * SO_TIMEOUT} is set. Returning {@code null} here is an abnormal condition that most server loops
 * do not explicitly handle; the chaos effect exercises the null-return handling path (or lack
 * thereof) in the application's acceptor loop.
 *
 * <p>Tomcat's BIO connector ({@code JIoEndpoint.Acceptor}) calls {@code serverSocket.accept()} in a
 * while loop; the returned socket is dispatched to a worker thread pool. If {@code accept()}
 * returns {@code null}, Tomcat's null-check discards the result and loops; the worker thread pool
 * is not occupied. This is distinct from the NIO connector (NioEndpoint) and APR connector, which
 * are not affected by this annotation.
 *
 * <p>Unlike {@link ChaosSocketAcceptDelay} which slows the accept rate while still making progress,
 * this annotation halts progress entirely. The suppress variant is appropriate when testing a
 * complete listen-path failure — for example, verifying that health checks report correctly or that
 * the application can be gracefully stopped even when the acceptor is not processing connections.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketAcceptSuppress
 * class TomcatAcceptorDeadlockTest {
 *   @Test
 *   void existingConnectionsWorkWhileNewOnesAreRejected(ConnectionInfo info) {
 *     // assert in-flight requests complete normally
 *     // assert new TCP connections fail after backlog fills
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
 * @see ChaosSocketAcceptDelay
 * @see com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelAcceptSuppress
 */
@Repeatable(ChaosSocketAcceptSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_ACCEPT)
public @interface ChaosSocketAcceptSuppress {

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
   * @ChaosSocketAcceptSuppress(id = "primary",  probability = 0.001)
   * @ChaosSocketAcceptSuppress(id = "replica",  probability = 0.01)
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
    ChaosSocketAcceptSuppress[] value();
  }
}
