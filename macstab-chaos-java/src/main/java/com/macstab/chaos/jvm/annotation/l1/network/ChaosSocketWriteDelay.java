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
 * Intercepts {@code SocketOutputStream.write()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before bytes are written to the kernel send buffer, simulating a saturated outbound
 * path or a slow network interface for blocking socket clients such as JDBC drivers sending queries
 * or legacy HTTP clients sending request bodies.
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
 *   <li>Before every call to {@code java.net.SocketOutputStream#write(byte[], int, int)} inside the
 *       target container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code write()} executes normally, flushing bytes from
 *       the caller's byte array into the kernel TCP send buffer.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The calling thread is blocked during the delay; for thread-per-connection servers, this
 *       holds the thread occupied; assert that the thread pool queue grows if requests arrive
 *       during the fault window and that the pool's queue capacity is not exceeded.
 *   <li>JDBC drivers write query packets via the socket output stream; the delay inflates the time
 *       from JDBC {@code execute()} being called to the query text arriving at the database; assert
 *       that query-level timeout tracking starts at the JDBC call, not at the socket write, to
 *       ensure the timeout budget is correctly accounted.
 *   <li>Apache HttpClient 4.x writes the HTTP request line, headers, and body through {@code
 *       SocketOutputStream.write()}; a write delay inflates the time-to-first-byte of the request;
 *       assert that server-side read timeouts do not fire before the full request is sent if the
 *       delay is shorter than the server's read timeout.
 *   <li><strong>Production failure mode:</strong> a network interface on the database client host
 *       becomes saturated due to a co-located background replication job; every outbound TCP write
 *       blocks in the kernel while the send buffer drains; JDBC query dispatch is inflated by the
 *       send delay; the database reports the client as idle (it has not sent anything yet); the
 *       database's idle connection timeout fires and closes the connection while the JDBC driver's
 *       write is still delayed — the driver then throws {@code IOException: Broken pipe} on the
 *       delayed write.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.SocketOutputStream#write(byte[], int, int)}, the
 * internal output stream returned by {@code Socket.getOutputStream()}. This path is used
 * exclusively by blocking socket clients; NIO-based frameworks use {@code
 * SocketChannel.write(ByteBuffer)} (see {@link
 * com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelWriteDelay}).
 *
 * <p>In blocking mode, {@code SocketOutputStream.write()} issues a {@code send(2)} syscall that
 * blocks until all bytes are copied to the kernel send buffer or an error occurs. The kernel send
 * buffer size is controlled by {@code SO_SNDBUF}; if the buffer is full (because the receiver's TCP
 * window is closed), the {@code send(2)} call blocks until space becomes available. The chaos delay
 * fires before this syscall, adding to the time before any bytes enter the kernel send buffer.
 *
 * <p>PostgreSQL JDBC sends query packets via {@code org.postgresql.core.PGStream.flush()}, which
 * calls {@code SocketOutputStream.write()} to send the buffered protocol bytes. The delay fires on
 * each write call within the flush; for large queries split across multiple writes, the total delay
 * is multiplied. The JDBC login timeout does not apply to individual write calls — only to the
 * initial connection establishment — so a long write delay can cause the query to take arbitrarily
 * longer than the configured statement timeout.
 *
 * <p>Combining this with {@link ChaosSocketReadDelay} creates a fully symmetric slow-link model for
 * blocking socket clients, equivalent to the NIO combination of {@link
 * com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelWriteDelay} and {@link
 * com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelReadDelay}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketWriteDelay(delayMs = 400)
 * class JdbcQueryDispatchDelayTest {
 *   @Test
 *   void queryTimeoutIsNotExhaustedByNetworkDelay(ConnectionInfo info) {
 *     // assert statement-level timeout starts after write completes
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
 * @see ChaosSocketWriteInjectException
 * @see ChaosSocketReadDelay
 * @see com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelWriteDelay
 */
@Repeatable(ChaosSocketWriteDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_WRITE)
public @interface ChaosSocketWriteDelay {

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
   * @ChaosSocketWriteDelay(id = "primary",  probability = 0.001)
   * @ChaosSocketWriteDelay(id = "replica",  probability = 0.01)
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
    ChaosSocketWriteDelay[] value();
  }
}
