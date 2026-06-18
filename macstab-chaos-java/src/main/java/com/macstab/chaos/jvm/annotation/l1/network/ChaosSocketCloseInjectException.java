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
 * Intercepts {@code Socket.close()} and throws the configured exception before the TCP connection
 * is torn down, simulating a close failure that leaves the socket open and the file descriptor
 * unreleased, exercising the rare but consequential error path where connection teardown itself
 * fails in JDBC drivers, HTTP clients, and connection pool eviction logic.
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
 *   <li>Before every call to {@code java.net.Socket#close()} inside the target container's JVM, the
 *       chaos agent intercepts the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; the real {@code close()} is not called;
 *       the file descriptor is not released and the TCP connection remains open.
 *   <li>The exception propagates to the caller — JDBC driver, HTTP client close path, or pool
 *       eviction code — which must handle a close failure or leak the socket.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Because the underlying socket is not closed, the file descriptor is not released; if many
 *       sockets fail to close, the application's file descriptor count grows; assert that the
 *       application's monitoring detects the file descriptor leak and that an alert fires before
 *       the OS limit is reached.
 *   <li>JDBC drivers' {@code java.sql.Connection.close()} implementations typically call {@code
 *       socket.close()} in a try-catch or finally block; if the close throws, the driver may
 *       swallow the exception or propagate it to the pool; HikariCP's eviction path catches
 *       exceptions from {@code close()} and logs them but does not retry — assert that the pool log
 *       captures the close failure and that the pool does not attempt to reuse the unclosed
 *       connection.
 *   <li>Applications that close connections in finally blocks will have the close exception
 *       propagate out of the finally block, potentially masking the original exception that caused
 *       the finally block to execute; assert that both the original exception and the close
 *       exception are logged and neither is silently lost.
 *   <li><strong>Production failure mode:</strong> a JVM run at the OS file descriptor limit ({@code
 *       ulimit -n}) combined with a connection pool that evicts connections and fails to close them
 *       causes the file descriptor count to grow monotonically; new connection attempts fail with
 *       {@code IOException: Too many open files}; the pool cannot create new connections; the
 *       application appears healthy (it is running) but cannot process any new requests that
 *       require database access.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.Socket#close()} via Byte Buddy. In the JVM, {@code
 * Socket.close()} calls {@code impl.close()}, which calls {@code socketClose()}, which issues
 * {@code close(2)} on the file descriptor. The chaos exception fires before any of this occurs; the
 * underlying OS socket remains open and the file descriptor is unreleased.
 *
 * <p>PostgreSQL JDBC's {@code AbstractJdbc2Connection.close()} calls {@code
 * protoConnection.close()} which eventually calls {@code pgStream.close()} and then {@code
 * socket.close()}. If the socket close throws, the JDBC connection object transitions to a closed
 * state internally (it sets {@code openStackTrace = null} and similar flags) even though the
 * underlying OS socket is not closed. This creates a split state: the JVM considers the connection
 * closed but the OS has an open socket with pending data in its send buffer.
 *
 * <p>HikariCP's {@code PoolBase.quietlyCloseConnection()} wraps the close in a try-catch, logs the
 * exception at WARN level, and does not propagate it. The pool's internal state is updated to
 * remove the connection from its tracking structures. Because the OS socket is still open, the
 * remote database server still holds the connection open; the database's {@code max_connections} is
 * not freed until the database's idle timeout fires and closes the socket from the server side.
 *
 * <p>This is one of the most difficult failure modes to test without chaos tooling because {@code
 * Socket.close()} almost never throws in production; its failure requires either kernel errors or
 * driver bugs. This annotation makes it reproducible and testable.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketCloseInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "close failed: transport endpoint is not connected")
 * class SocketCloseFailureLeakTest {
 *   @Test
 *   void fileDescriptorLeakIsDetectedAndAlertFires(ConnectionInfo info) {
 *     // assert FD count grows and monitoring alert fires before OS limit
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
 * @see ChaosSocketCloseDelay
 */
@Repeatable(ChaosSocketCloseInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_CLOSE)
public @interface ChaosSocketCloseInjectException {

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
   * @ChaosSocketCloseInjectException(id = "primary",  probability = 0.001)
   * @ChaosSocketCloseInjectException(id = "replica",  probability = 0.01)
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
    ChaosSocketCloseInjectException[] value();
  }
}
