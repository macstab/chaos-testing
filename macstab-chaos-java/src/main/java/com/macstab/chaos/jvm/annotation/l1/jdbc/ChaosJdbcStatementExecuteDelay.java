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
 * Intercepts {@code Statement.execute()} / {@code executeQuery()} / {@code executeUpdate()} and
 * holds the calling thread for {@link #delayMs()} milliseconds before the SQL is sent to the
 * database, simulating a slow query or a database under CPU/IO pressure.
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
 *   <li>Before every call to {@code java.sql.Statement#execute(String)}, {@code
 *       executeQuery(String)}, or {@code executeUpdate(String)} inside the target container's JVM,
 *       the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns to the caller and the underlying statement execute proceeds normally,
 *       sending SQL to the database and waiting for the result.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every SQL statement execution takes at least {@link #delayMs()} ms longer; assert that the
 *       application's JDBC query timeout (via {@code Statement.setQueryTimeout()}) fires.
 *   <li>Spring's {@code @Transactional(timeout)} counts the transaction wall-clock time from
 *       connection acquisition; a long statement delay will push transactions past this timeout,
 *       triggering {@code TransactionTimedOutException}.
 *   <li>ORM batch operations: Hibernate's batch flush calls {@code executeBatch()} on a {@code
 *       PreparedStatement}; delay here inflates flush time and may cause session-scoped timeouts to
 *       fire mid-transaction.
 *   <li><strong>Production failure mode:</strong> a missing index causes a full table scan on a
 *       50-million-row table; every {@code SELECT} takes 8 s, JDBC threads block, the connection
 *       pool fills, and the application stops serving traffic within 30 s of the bad deployment.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.sql.Statement} at the interface level, matching all
 * concrete implementations: JDBC driver-specific implementations (PostgreSQL's {@code PgStatement},
 * MySQL Connector/J's {@code StatementImpl}), and pool-wrapped statements from HikariCP ({@code
 * HikariProxyStatement}) and DBCP2 ({@code DelegatingStatement}). Byte Buddy matches by interface
 * method signature, so the intercept fires regardless of which pool or driver is in use.
 *
 * <p>The delay fires before the statement's network write, meaning the connection's socket is held
 * open during the sleep. If the database has a server-side idle-connection timeout shorter than the
 * injected delay, the connection may be killed by the database during the sleep; the subsequent
 * socket write will then fail with a broken-pipe {@code SQLException}. This compound failure
 * (injected delay + server kill) is a realistic model of what happens when a database is under
 * heavy load and aggressively evicting idle connections.
 *
 * <p>JDBC's {@code Statement.setQueryTimeout(seconds)} is checked by the driver after the execute
 * begins; because the chaos delay fires before the execute, it adds to the total observed query
 * time. If the sum of the injected delay and the real query time exceeds the query timeout, the
 * driver throws {@code SQLTimeoutException}. This is the correct behavior to test: the application
 * should handle {@code SQLTimeoutException} gracefully.
 *
 * <p>For prepared statements specifically, see {@link ChaosJdbcPreparedStatementDelay}, which
 * targets {@code PreparedStatement.execute()} / {@code executeQuery()} / {@code executeUpdate()} on
 * the parameterised variant. Both annotations may be combined to cover all JDBC statement paths
 * simultaneously.
 *
 * <p>Combining with {@link ChaosJdbcTransactionCommitDelay} simulates the worst-case scenario where
 * both query execution and commit are slow, compounding latency across the full transaction
 * lifecycle — the model for a database performing a large UNDO/REDO log write.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcStatementExecuteDelay(delayMs = 3000)
 * class SlowQueryTest {
 *   @Test
 *   void transactionTimesOutAndRollsBack(ConnectionInfo info) {
 *     // assert TransactionTimedOutException and HTTP 503
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
 * @see ChaosJdbcStatementExecuteInjectException
 * @see ChaosJdbcPreparedStatementDelay
 * @see ChaosJdbcTransactionCommitDelay
 */
@Repeatable(ChaosJdbcStatementExecuteDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_STATEMENT_EXECUTE)
public @interface ChaosJdbcStatementExecuteDelay {

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
   * @ChaosJdbcStatementExecuteDelay(id = "primary",  probability = 0.001)
   * @ChaosJdbcStatementExecuteDelay(id = "replica",  probability = 0.01)
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
    ChaosJdbcStatementExecuteDelay[] value();
  }
}
