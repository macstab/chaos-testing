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
 * Intercepts {@code Socket.close()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the TCP connection is torn down and the file descriptor is released,
 * simulating slow connection teardown that delays pool slot recycling and inflates the time between
 * requests in blocking-socket client frameworks.
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
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code close()} executes normally, sending TCP FIN,
 *       closing the file descriptor, and releasing the socket's OS resources.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Connection pools that close expired or evicted connections will hold their eviction thread
 *       busy for the delay duration; HikariCP's {@code HouseKeeper} thread, which runs pool
 *       maintenance tasks, will be blocked during the close if it performs the close directly;
 *       assert that pool maintenance does not starve normal connection acquisition.
 *   <li>HTTP clients that close connections after each request (not keep-alive) will take longer
 *       per request due to the close delay; assert that the application's request rate drops to
 *       approximately {@code 1000 / delayMs} requests per second when connections are not reused.
 *   <li>The file descriptor for the socket is not released until the real close executes; if many
 *       sockets are simultaneously waiting to close, the application's file descriptor count
 *       increases; assert that the application does not exceed OS limits (ulimit -n) during the
 *       fault window.
 *   <li><strong>Production failure mode:</strong> a high-throughput batch job closes thousands of
 *       short-lived database connections per second; if each close is slow (e.g. due to SO_LINGER
 *       enabled with a non-zero timeout), the batch threads are blocked in close; the file
 *       descriptor table fills; subsequent connect attempts fail with {@code IOException: Too many
 *       open files}; the application must be restarted to recover.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.Socket#close()}. The JVM's close implementation calls
 * {@code close(2)} on the file descriptor, which sends a TCP FIN to the peer. If {@code SO_LINGER}
 * is set with a non-zero timeout, the close blocks in the kernel until the peer acknowledges the
 * FIN or the linger timeout expires. The chaos delay fires before this kernel call, adding a
 * predictable JVM-level delay independent of the linger configuration.
 *
 * <p>HikariCP's connection eviction path: when a connection is deemed stale (exceeded {@code
 * maxLifetime} or failed validation), HikariCP closes the underlying connection object. For JDBC
 * connections, this calls {@code java.sql.Connection.close()}, which eventually calls the driver's
 * physical socket close. The chaos delay fires here, holding the eviction path open. If HikariCP
 * evicts multiple connections simultaneously (e.g. after a pool reset), the eviction thread is
 * blocked for {@code delayMs × evictedCount} ms.
 *
 * <p>The delay does not affect the logical state of the socket — the socket is still considered
 * open by the JVM during the delay. Code that checks {@code socket.isClosed()} before the actual
 * close returns {@code false} during the sleep. This is the correct model: the socket is open until
 * close completes, and the delay simulates a slow close path in the OS or kernel.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketCloseDelay(delayMs = 200)
 * class PoolEvictionLatencyTest {
 *   @Test
 *   void hikariCpEvictionDoesNotStarveConnectionAcquisition(ConnectionInfo info) {
 *     // assert that pool maintenance and acquisition run concurrently without starvation
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
 * @see ChaosSocketCloseInjectException
 * @see ChaosSocketConnectDelay
 */
@Repeatable(ChaosSocketCloseDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_CLOSE)
public @interface ChaosSocketCloseDelay {

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
   * @ChaosSocketCloseDelay(id = "primary",  probability = 0.001)
   * @ChaosSocketCloseDelay(id = "replica",  probability = 0.01)
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
    ChaosSocketCloseDelay[] value();
  }
}
