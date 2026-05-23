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
 * Intercepts {@code SocketInputStream.read()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before bytes are read from the kernel receive buffer, inflating read latency for
 * blocking socket clients such as JDBC drivers, legacy HTTP clients, and Kafka consumers that use
 * {@code java.net.Socket}-based I/O.
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
 *   <li>Before every call to {@code java.net.SocketInputStream#read(byte[], int, int)} inside the
 *       target container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code read()} executes normally, blocking until data
 *       is available in the kernel receive buffer and copying it to the caller's byte array.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The thread that calls {@code read()} is blocked for at least {@link #delayMs()} ms on
 *       every read; in thread-per-request servers (Tomcat), each active connection occupies its
 *       thread for the extra duration; assert that thread pool utilisation increases and that the
 *       application's thread pool queue fills if requests arrive faster than threads complete.
 *   <li>If the delay exceeds the socket's read timeout ({@code SO_TIMEOUT} set via
 *       {@code Socket.setSoTimeout()}), the timeout fires after the sleep and the read itself
 *       never executes; the caller receives {@code SocketTimeoutException}; assert that the
 *       application distinguishes a read timeout from a connection reset.
 *   <li>JDBC drivers that use blocking reads for query response parsing experience inflated query
 *       times; HikariCP's statement-level timeout (if configured) may expire during the read
 *       delay; assert that the connection is properly evicted rather than returned to the pool.
 *   <li><strong>Production failure mode:</strong> a database runs a long query that returns a
 *       large result set in multiple TCP segments; the application's JDBC thread blocks on
 *       {@code SocketInputStream.read()} waiting for each segment; if the DB server is under GC
 *       pressure it pauses sending; the Tomcat thread is blocked for the GC pause duration;
 *       during this time the Tomcat connector cannot allocate threads for new requests, causing
 *       cascading request queuing and timeout failures.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.SocketInputStream#read(byte[], int, int)}, the
 * internal stream used by {@code Socket.getInputStream()}. Unlike NIO
 * {@code SocketChannel.read(ByteBuffer)} which is used by Netty and async frameworks, this path
 * is used exclusively by blocking socket clients. The read call ultimately issues a {@code recv(2)}
 * syscall; the chaos delay fires before this syscall, holding the calling thread.
 *
 * <p>PostgreSQL JDBC's {@code org.postgresql.core.PGStream} wraps the socket input stream and
 * calls {@code read()} to receive protocol messages from the database. Every query response, every
 * COPY data chunk, and every notification is received via this path. The delay fires on each read
 * call, compounding for large result sets that arrive in multiple reads.
 *
 * <p>The {@code SO_TIMEOUT} set via {@code Socket.setSoTimeout()} is enforced by the OS-level
 * {@code SO_RCVTIMEO} socket option. The chaos delay fires in the JVM before the syscall; if the
 * delay itself exceeds the SO_TIMEOUT value, the timeout fires immediately when {@code recv(2)} is
 * called after the sleep — the OS does not know about the JVM sleep. This creates an apparent
 * read timeout even when the sender is responsive, which is a realistic simulation of a slow
 * consumer exceeding its own timeout budget.
 *
 * <p>Kafka's Java consumer uses {@code kafka.network.Selector} which is NIO-based for the
 * consumer group protocol, but the older {@code SimpleConsumer} (Kafka 0.x/1.x) used blocking
 * sockets; this annotation applies to legacy Kafka integrations. Modern Kafka clients should use
 * {@link ChaosNioChannelReadDelay} instead.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketReadDelay(delayMs = 500)
 * class JdbcReadTimeoutTest {
 *   @Test
 *   void hikariCpEvictsConnectionOnReadTimeout(ConnectionInfo info) {
 *     // assert that SocketTimeoutException is mapped to SQLTimeoutException
 *     // assert connection is not returned to pool after timeout
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
 * @see ChaosSocketReadInjectException
 * @see ChaosSocketWriteDelay
 * @see ChaosNioChannelReadDelay
 */
@Repeatable(ChaosSocketReadDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_READ)
public @interface ChaosSocketReadDelay {

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
   * @ChaosSocketReadDelay(id = "primary",  probability = 0.001)
   * @ChaosSocketReadDelay(id = "replica",  probability = 0.01)
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
    ChaosSocketReadDelay[] value();
  }
}
