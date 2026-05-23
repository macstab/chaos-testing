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
 * Intercepts {@code Connection.commit()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the transaction is flushed to the database, simulating a slow database
 * commit caused by heavy WAL/redo-log I/O, lock contention, or fsync pressure.
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
 *   <li>Before every call to {@code java.sql.Connection#commit()} inside the target container's
 *       JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code commit()} executes normally; the database
 *       durably writes the transaction.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every committed transaction takes at least {@link #delayMs()} ms longer; rows remain
 *       locked at the SERIALIZABLE or REPEATABLE READ isolation level for the duration, blocking
 *       other transactions that request the same rows.
 *   <li>Spring's {@code @Transactional(timeout)} counts from the transaction's begin; commit
 *       delay adds to that total. If the sum exceeds the timeout, the commit throws
 *       {@code TransactionTimedOutException} — assert the rollback path handles this.
 *   <li>Distributed systems using two-phase commit (XA transactions) hold the prepare lock
 *       during the commit; a delayed commit keeps the XA resource locked, potentially causing
 *       other participants' transactions to fail with lock-wait timeout.
 *   <li><strong>Production failure mode:</strong> a database storage node's disk subsystem
 *       stalls during fsync; every {@code commit()} takes 5–10 s; database connections are held
 *       in the commit phase, the pool fills, and no new requests can acquire connections while
 *       existing transactions hang mid-commit — an incident that looks like "no database
 *       connections available" but is actually "commit stalled".
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.sql.Connection#commit()} at the interface level,
 * covering all JDBC drivers and pool-wrapped connections. The chaos delay fires before the
 * JDBC driver's network write of the commit command to the database. During the sleep, the
 * transaction's row-level locks remain held at the database (the database does not know the
 * commit is coming yet), so the delay compounds lock-wait time for other transactions.
 *
 * <p>PostgreSQL uses the simple query protocol for {@code COMMIT}; the commit round-trip is
 * typically sub-millisecond on a local database but can take seconds under write-heavy load when
 * the WAL writer queue is full. Injecting commit delay simulates this without needing to generate
 * the actual I/O load, which is useful for isolated unit testing of lock-contention scenarios.
 *
 * <p>Spring's {@code DataSourceTransactionManager} calls {@code Connection.commit()} inside a
 * {@code finally} block after {@code @Transactional} method execution. If the commit throws (due
 * to the application's own transaction timeout), the manager calls {@code rollback()} as a
 * fallback. The chaos delay may cause this sequence — commit delay → timeout → rollback attempt —
 * which is a realistic scenario for testing the exception message and log output that accompanies
 * a timed-out commit.
 *
 * <p>HikariCP wraps {@code commit()} via {@code ProxyConnection}; the chaos intercept fires on
 * the wrapper's method, so HikariCP's pool statistics (in-use time, transaction duration
 * histograms) will accurately reflect the injected delay in their recorded values.
 *
 * <p>Combining with {@link ChaosJdbcTransactionRollbackDelay} allows testing the scenario where
 * both commit and rollback are slow — the worst-case for a database under storage pressure where
 * even undoing work requires writing to the undo log, which is also backed by the stalled disk.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcTransactionCommitDelay(delayMs = 8000)
 * class CommitStallTest {
 *   @Test
 *   void requestsBlockDuringCommitAndPoolExhausts(ConnectionInfo info) {
 *     // assert pool exhaustion metric and eventual HTTP 503
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
 * @see ChaosJdbcTransactionCommitInjectException
 * @see ChaosJdbcTransactionRollbackDelay
 * @see ChaosJdbcStatementExecuteDelay
 */
@Repeatable(ChaosJdbcTransactionCommitDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_TRANSACTION_COMMIT)
public @interface ChaosJdbcTransactionCommitDelay {

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
   * @ChaosJdbcTransactionCommitDelay(id = "primary",  probability = 0.001)
   * @ChaosJdbcTransactionCommitDelay(id = "replica",  probability = 0.01)
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
    ChaosJdbcTransactionCommitDelay[] value();
  }
}
