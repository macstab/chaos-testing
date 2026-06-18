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
 * Intercepts {@code SocketInputStream.read()} and throws the configured exception before any bytes
 * are read from the kernel receive buffer, simulating a mid-transfer connection reset or read error
 * as seen by blocking socket clients such as JDBC drivers, legacy HTTP clients, and Kafka
 * consumers.
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
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no bytes are read from the kernel
 *       receive buffer.
 *   <li>The exception propagates to the caller — JDBC driver, HTTP client, or raw socket user — as
 *       if the OS returned an error from the {@code recv(2)} syscall.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>JDBC drivers catch the {@code IOException} during query-response parsing and wrap it in a
 *       driver-specific {@code SQLException}; PostgreSQL wraps it as {@code PSQLException} with SQL
 *       state {@code 08006} (connection failure during transaction); assert that HikariCP evicts
 *       the connection and does not return it to the pool.
 *   <li>Inject {@code java.net.SocketTimeoutException} to simulate a read timeout mid-response;
 *       JDBC drivers typically close the connection on a read timeout rather than retrying; assert
 *       that outstanding result set iterators are invalidated.
 *   <li>Apache HttpClient 4.x wraps the read exception in {@code NoHttpResponseException} if the
 *       connection was closed before the response status line was received; assert that the retry
 *       handler distinguishes this from a mid-response failure on a non-idempotent request.
 *   <li><strong>Production failure mode:</strong> a database firewall kills idle connections with
 *       TCP RST after a configurable idle timeout; the JDBC driver's read call on the next query
 *       receives {@code IOException: Connection reset by peer}; HikariCP catches the exception,
 *       validates the connection, finds it broken, evicts it, and creates a new connection;
 *       applications without connection validation see {@code SQLRecoverableException} propagated
 *       to their DAO layer.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.SocketInputStream#read(byte[], int, int)}. PostgreSQL
 * JDBC's {@code org.postgresql.core.PGStream.receive(int)} calls this method to read the exact
 * number of bytes of a protocol message. If the read throws, the driver's {@code catch
 * (IOException)} block in {@code QueryExecutorImpl} closes the connection and sets the connection's
 * state to closed. Any subsequent use of the {@code Connection} object throws {@code PSQLException:
 * This connection has been closed}.
 *
 * <p>MySQL Connector/J reads protocol packets via {@code
 * com.mysql.cj.protocol.a.SimplePacketReader.readMessageLocal()}, which calls the socket input
 * stream's {@code read()}. An exception here causes the connector to call {@code realClose(false)}
 * on the connection and throw {@code CJCommunicationsException}. HikariCP's connection proxy
 * catches this during {@code isValid()} and discards the connection without returning it to the
 * pool.
 *
 * <p>Exception classification matters for pool behaviour: if the exception is classified as
 * transient (e.g. {@code SQLRecoverableException}), pools may attempt to reconnect and retry; if
 * classified as non-transient (e.g. {@code SQLNonTransientException}), the pool may report the
 * database as unreachable and stop creating new connections. This annotation lets you inject both
 * types to test both pool behaviours.
 *
 * <p>Unlike {@link com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelReadInjectException}
 * which targets the NIO path used by Netty, this annotation targets the blocking stream path. They
 * are mutually exclusive per connection: Netty connections use NIO channels, JDBC connections use
 * blocking sockets. Applying both annotations simultaneously will fault both families.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketReadInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "Connection reset by peer")
 * class JdbcConnectionResetTest {
 *   @Test
 *   void hikariCpEvictsAndPoolReplenishes(ConnectionInfo info) {
 *     // assert connection is evicted and pool creates a new one
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
 * @see ChaosSocketReadDelay
 * @see ChaosSocketWriteInjectException
 * @see com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelReadInjectException
 */
@Repeatable(ChaosSocketReadInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_READ)
public @interface ChaosSocketReadInjectException {

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
   * @ChaosSocketReadInjectException(id = "primary",  probability = 0.001)
   * @ChaosSocketReadInjectException(id = "replica",  probability = 0.01)
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
    ChaosSocketReadInjectException[] value();
  }
}
