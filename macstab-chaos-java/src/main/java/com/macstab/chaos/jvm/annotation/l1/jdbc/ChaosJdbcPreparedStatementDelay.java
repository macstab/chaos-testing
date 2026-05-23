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
 * Intercepts {@code PreparedStatement.execute()} / {@code executeQuery()} /
 * {@code executeUpdate()} and holds the calling thread for {@link #delayMs()} milliseconds before
 * the parameterised SQL is sent to the database, simulating slow parameterised queries used by ORM
 * frameworks like Hibernate and JPA.
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
 *   <li>Before every call to {@code java.sql.PreparedStatement#execute()},
 *       {@code executeQuery()}, or {@code executeUpdate()} inside the target container's JVM,
 *       the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying prepared statement execute proceeds normally, sending
 *       the pre-compiled SQL with bound parameters to the database.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every parameterised SQL execution takes at least {@link #delayMs()} ms longer; in
 *       Hibernate applications this inflates {@code Session.flush()} duration when dirty entities
 *       are persisted in batches.
 *   <li>Spring Data JPA repository methods ({@code save()}, {@code findById()}) delegate to
 *       prepared statements; assert that repository call latency includes the injected overhead.
 *   <li>JPA's optimistic locking: a delayed {@code UPDATE} statement may push the transaction
 *       past its timeout, causing {@code OptimisticLockException} or
 *       {@code TransactionTimedOutException} — assert that the retry logic handles these.
 *   <li><strong>Production failure mode:</strong> a large Hibernate batch flush with 500
 *       dirty entities takes 8 s; each prepared statement execute is delayed by database
 *       lock contention; the transaction times out mid-flush and Hibernate's second-level
 *       cache becomes inconsistent with the database state.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.sql.PreparedStatement} — a sub-interface of
 * {@code Statement} — matching the no-argument execute variants that use pre-bound parameters.
 * This is distinct from {@link ChaosJdbcStatementExecuteDelay} which targets the
 * {@code Statement} execute methods taking a SQL string argument. In practice, Hibernate and
 * Spring Data use prepared statements for all parameterised operations, making this annotation
 * the primary tool for ORM-level query latency simulation.
 *
 * <p>PostgreSQL's JDBC driver ({@code org.postgresql.jdbc.PgPreparedStatement}) implements the
 * {@code PreparedStatement} interface and sends a binary-format query over the libpq protocol.
 * MySQL Connector/J's {@code ClientPreparedStatement} may use server-side or client-side
 * preparation depending on configuration. The chaos delay fires before either driver's network
 * write, so both are affected identically regardless of preparation mode.
 *
 * <p>Hibernate's flush ordering is deterministic per the JPA spec (inserts before updates before
 * deletes by default), but the injected delay applies to each statement independently, so a batch
 * of 100 inserts accumulates 100 × delay. This exponential blow-up accurately models what
 * happens when a database lock causes each statement to wait for lock acquisition — a common
 * cause of transaction timeout cascades.
 *
 * <p>The delay fires before parameter transmission, meaning parameters are already bound in the
 * driver's internal buffer. No parameter data is lost; the sleep merely postpones the network
 * write. The prepared statement remains valid and reusable after the sleep; the chaos effect does
 * not invalidate the statement cache.
 *
 * <p>Combining this with {@link ChaosJdbcConnectionAcquireDelay} accurately models the
 * double-latency scenario of a database under load: both acquiring a connection from the pool and
 * executing each query are slow, which is the typical observed symptom of database CPU saturation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcPreparedStatementDelay(delayMs = 500)
 * class OrmBatchFlushLatencyTest {
 *   @Test
 *   void batchFlushTimesOut(ConnectionInfo info) {
 *     // assert TransactionTimedOutException when flush exceeds transaction timeout
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
 * @see ChaosJdbcStatementExecuteDelay
 * @see ChaosJdbcPreparedStatementInjectException
 * @see ChaosJdbcTransactionCommitDelay
 */
@Repeatable(ChaosJdbcPreparedStatementDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_PREPARED_STATEMENT)
public @interface ChaosJdbcPreparedStatementDelay {

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
   * @ChaosJdbcPreparedStatementDelay(id = "primary",  probability = 0.001)
   * @ChaosJdbcPreparedStatementDelay(id = "replica",  probability = 0.01)
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
    ChaosJdbcPreparedStatementDelay[] value();
  }
}
