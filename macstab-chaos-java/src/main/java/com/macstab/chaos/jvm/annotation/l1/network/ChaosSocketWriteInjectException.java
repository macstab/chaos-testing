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
 * Intercepts {@code SocketOutputStream.write()} and throws the configured exception before any
 * bytes are written to the kernel send buffer, simulating a broken-pipe failure mid-request for
 * blocking socket clients such as JDBC drivers dispatching queries or legacy HTTP clients streaming
 * request bodies.
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
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no bytes are written to the kernel send
 *       buffer.
 *   <li>The exception propagates to the caller — JDBC driver, HTTP client, or raw socket user — as
 *       if the OS returned an error from the {@code send(2)} syscall (e.g. {@code EPIPE}).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>JDBC drivers catch the {@code IOException} during query dispatch and wrap it in a
 *       driver-specific {@code SQLException}; the socket is in an unknown state (partial write may
 *       have occurred in a previous write call); the driver closes the connection; assert that
 *       HikariCP evicts the connection rather than validating and returning it.
 *   <li>Inject {@code java.io.IOException: Broken pipe} to simulate the server closing the
 *       connection between two consecutive requests on a keep-alive HTTP connection; Apache
 *       HttpClient 4.x will retry the request once on a new connection if the request is
 *       idempotent; assert that non-idempotent requests are not retried.
 *   <li>PostgreSQL JDBC wraps the write exception as {@code PSQLException} with SQL state {@code
 *       08006} (connection failure); Spring's {@code SQLExceptionTranslator} maps this to {@code
 *       DataAccessResourceFailureException}; assert that the application's service layer handles
 *       this as a transient error and triggers a retry.
 *   <li><strong>Production failure mode:</strong> a load balancer terminates idle connections with
 *       no TCP FIN (hard RST) after its idle timeout; the JDBC connection pool has not validated
 *       the connection because the pool's {@code keepaliveTime} is longer than the load balancer's
 *       idle timeout; the next query write to the dead connection throws {@code IOException: Broken
 *       pipe}; the pool evicts the connection and creates a new one to bypass the load balancer;
 *       applications without pool-level keepalive see periodic {@code
 *       DataAccessResourceFailureException} on the first query after an idle period.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.SocketOutputStream#write(byte[], int, int)}.
 * PostgreSQL JDBC's {@code PGStream.flush()} buffers protocol bytes in a {@code
 * BufferedOutputStream} and calls {@code flush()} which calls the underlying {@code
 * SocketOutputStream.write()} with the accumulated buffer. The chaos exception fires on the write
 * call, causing the buffered query bytes to not reach the database. The driver's exception handler
 * in {@code QueryExecutorImpl} closes the physical connection and the logical {@code Connection}
 * object transitions to a closed state.
 *
 * <p>The distinction from {@link ChaosSocketReadInjectException} is timing: a write exception
 * occurs before the query reaches the database (the query is lost); a read exception occurs after
 * the query has been sent and the database has processed it (the response is lost). Write failures
 * are always safe to retry from a data-consistency standpoint (the database never saw the query);
 * read failures may not be (the database may have committed before the exception).
 *
 * <p>HTTP clients that send chunked request bodies write multiple chunks via multiple {@code
 * write()} calls; the exception fires on the first call, before the first chunk reaches the server;
 * the server sees a broken connection before receiving the complete request body. Servers using
 * request body buffering will discard the partial request; servers streaming the body may process a
 * partial body, which can cause data corruption if the application does not validate completeness.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketWriteInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "Broken pipe")
 * class JdbcBrokenPipeTest {
 *   @Test
 *   void connectionIsEvictedAndQueryIsLostNotDuplicated(ConnectionInfo info) {
 *     // assert connection eviction and no duplicate DB writes
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
 * @see ChaosSocketWriteDelay
 * @see ChaosSocketReadInjectException
 * @see ChaosNioChannelWriteInjectException
 */
@Repeatable(ChaosSocketWriteInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_WRITE)
public @interface ChaosSocketWriteInjectException {

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
   * @ChaosSocketWriteInjectException(id = "primary",  probability = 0.001)
   * @ChaosSocketWriteInjectException(id = "replica",  probability = 0.01)
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
    ChaosSocketWriteInjectException[] value();
  }
}
