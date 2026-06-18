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
 * Intercepts {@code Connection.rollback()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the rollback command is sent to the database, simulating a slow undo-log
 * write or a database under I/O pressure where even error recovery is delayed.
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
 *   <li>Before every call to {@code java.sql.Connection#rollback()} inside the target container's
 *       JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code rollback()} executes normally; the database
 *       writes undo records and releases row-level locks.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every rollback takes at least {@link #delayMs()} ms longer; during this time, row-level
 *       locks are still held at the database, blocking other transactions that want those rows.
 *   <li>JDBC connection pools mark connections as returned only after rollback completes; a slow
 *       rollback delays connection return to the pool, effectively reducing pool throughput.
 *   <li>Error-handling code paths that call rollback (typically in {@code catch} or {@code finally}
 *       blocks) will block for the duration; assert that the application does not hold additional
 *       locks (e.g. Java-level locks) during this period.
 *   <li><strong>Production failure mode:</strong> a long-running transaction is rolled back due to
 *       a serialization failure; the rollback itself takes 12 s because the undo log is large and
 *       the database I/O is saturated; the connection is held, the pool slot is unavailable, and
 *       the service effectively has one fewer worker thread for 12 s per rollback.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.sql.Connection#rollback()} at the interface level. In a
 * database like PostgreSQL, rollback requires writing an {@code ABORT} record to the WAL and
 * updating the transaction's commit status in the CLOG. Under heavy write load the WAL writer may
 * be backlogged; injecting rollback delay simulates this without needing to generate actual WAL
 * pressure.
 *
 * <p>Spring's {@code DataSourceTransactionManager.doRollback()} calls this method in its rollback
 * path, which is invoked from the transaction proxy when the {@code @Transactional} method throws
 * an exception. The rollback delay therefore inflates the total time between the business exception
 * being thrown and the caller receiving the response — a hidden cost that is rarely measured in
 * synthetic load tests.
 *
 * <p>HikariCP marks a connection as "not in transaction" only after {@code rollback()} returns.
 * During the chaos sleep, the connection is counted as "in use" by the pool, meaning the pool may
 * appear to have fewer available connections than it actually does. Combine with a short pool
 * timeout to expose pool-exhaustion conditions that occur specifically during rollback storms (high
 * error rates causing many concurrent rollbacks).
 *
 * <p>The delay fires before the network write of the rollback command. Row locks are released only
 * after the rollback ACK is received from the database; during the sleep, the database is still
 * waiting for the rollback command and the locks remain held. This is different from a network
 * partition (where the connection drops and the database automatically rolls back after detecting
 * the lost connection via TCP keepalives).
 *
 * <p>Combining with {@link ChaosJdbcTransactionCommitDelay} models a database under storage
 * pressure where both commit and rollback are slow — the pathological case of a database with a
 * full transaction log or saturated storage subsystem.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcTransactionRollbackDelay(delayMs = 5000)
 * class SlowRollbackTest {
 *   @Test
 *   void connectionReturnedToPoolAfterRollback(ConnectionInfo info) {
 *     // assert pool connections are eventually returned despite slow rollback
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
 * @see ChaosJdbcTransactionRollbackInjectException
 * @see ChaosJdbcTransactionCommitDelay
 */
@Repeatable(ChaosJdbcTransactionRollbackDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_TRANSACTION_ROLLBACK)
public @interface ChaosJdbcTransactionRollbackDelay {

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
   * @ChaosJdbcTransactionRollbackDelay(id = "primary",  probability = 0.001)
   * @ChaosJdbcTransactionRollbackDelay(id = "replica",  probability = 0.01)
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
    ChaosJdbcTransactionRollbackDelay[] value();
  }
}
