/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.jdbc;

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
 * Intercepts {@code DataSource.getConnection()} and delays the calling thread for
 * {@link #delayMs()} milliseconds before returning a connection, simulating a slow or
 * saturated JDBC connection pool and the associated request-queuing latency.
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
 *   <li>Before every call to {@code javax.sql.DataSource#getConnection()} inside the target
 *       container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns to the caller and the underlying {@code getConnection()} executes
 *       normally, returning a real connection from the pool.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every database operation takes at least {@link #delayMs()} ms longer at the connection
 *       acquisition step; assert that request response times include this overhead.
 *   <li>Connection pool timeouts (HikariCP's {@code connectionTimeout}, C3P0's
 *       {@code checkoutTimeout}) configured shorter than the injected delay will now fire;
 *       assert that the application converts pool timeout exceptions to HTTP 503.
 *   <li>Spring's {@code @Transactional} methods acquire a connection at transaction begin; a
 *       delay here pushes the total transaction time over ORM-level statement timeouts.
 *   <li><strong>Production failure mode:</strong> a connection leak in one service causes the
 *       pool to fill; {@code getConnection()} blocks for {@code connectionTimeout} (30 s) on
 *       every request, the thread pool saturates, health-checks fail, and the instance is removed
 *       from the load balancer while the root cause (the leak) goes undetected.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code javax.sql.DataSource#getConnection()} at the interface
 * method level. Byte Buddy matches any class implementing {@code DataSource} whose
 * {@code getConnection()} is called, which covers HikariCP's {@code HikariDataSource},
 * Apache DBCP2's {@code BasicDataSource}, and Spring's {@code DriverManagerDataSource}.
 * Because the intercept fires before the pool's own wait logic, the injected delay compounds with
 * any real pool-wait time: if the pool is already under pressure, the observed delay is the sum
 * of both.
 *
 * <p>HikariCP's pool implementation parks the acquiring thread on a
 * {@code java.util.concurrent.SynchronousQueue} or {@code LinkedBlockingDeque} when no
 * connections are immediately available. The chaos delay fires before this park, so even when a
 * connection is immediately available the delay is observable. This makes the fault
 * deterministic regardless of pool utilisation level.
 *
 * <p>When multiple threads simultaneously acquire connections with a delay, the pool's
 * {@code maximumPoolSize} acts as a ceiling on concurrency. The injected threads still hold their
 * application threads in sleep, inflating thread-pool utilisation without adding pressure to the
 * database itself. This is the correct model for testing "pool wait" scenarios without actually
 * starving the database.
 *
 * <p>In Spring applications using JPA, {@code LocalContainerEntityManagerFactoryBean} wraps the
 * {@code DataSource}; connection acquisition occurs inside {@code EntityManager.getTransaction()
 * .begin()} when the transaction isolation level requires an exclusive connection. The delay
 * therefore inflates transaction begin latency, which is a common hidden cost in high-throughput
 * OLTP systems.
 *
 * <p>Combining this annotation with {@link ChaosJdbcTransactionCommitDelay} simulates a database
 * under memory pressure where both acquiring a connection and flushing dirty pages are slow,
 * producing compounding latency that mirrors real GC-pause-induced database stalls.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcConnectionAcquireDelay(delayMs = 5000)
 * class PoolTimeoutTest {
 *   @Test
 *   void applicationReturns503WhenPoolTimesOut(ConnectionInfo info) {
 *     // assert HTTP 503 and pool-timeout metric incremented
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
 * @see ChaosJdbcConnectionAcquireInjectException
 * @see ChaosJdbcStatementExecuteDelay
 */
@Repeatable(ChaosJdbcConnectionAcquireDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_CONNECTION_ACQUIRE)
public @interface ChaosJdbcConnectionAcquireDelay {

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
   * @ChaosJdbcConnectionAcquireDelay(id = "primary",  probability = 0.001)
   * @ChaosJdbcConnectionAcquireDelay(id = "replica",  probability = 0.01)
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
    ChaosJdbcConnectionAcquireDelay[] value();
  }
}
