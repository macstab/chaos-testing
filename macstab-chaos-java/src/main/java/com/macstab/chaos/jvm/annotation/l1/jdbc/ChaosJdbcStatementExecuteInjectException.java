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
 * throws the configured exception before SQL is sent to the database, enabling tests to verify that
 * the application handles specific JDBC exception types raised during query execution.
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
 *   <li>Before every call to {@code java.sql.Statement#execute(String)},
 *       {@code executeQuery(String)}, or {@code executeUpdate(String)} inside the target
 *       container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it immediately.
 *   <li>No SQL is sent to the database; no JDBC network activity occurs; the connection remains
 *       valid and is returned to the pool after the transaction manager handles the exception.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every statement execute throws the configured exception; Spring's {@code JdbcTemplate}
 *       translates it via {@code SQLExceptionTranslator} — assert that the correct
 *       {@code DataAccessException} subtype is produced.
 *   <li>JPA/Hibernate: an exception during flush causes the persistence context to be marked
 *       invalid; assert that the entity manager is closed and not reused.
 *   <li>Inject {@code java.sql.SQLTimeoutException} to test query-timeout handling without
 *       waiting for a real slow query; assert that the application increments a timeout counter.
 *   <li><strong>Production failure mode:</strong> a database schema migration runs
 *       {@code ALTER TABLE} acquiring an exclusive lock; concurrent application queries receive
 *       {@code com.mysql.cj.jdbc.exceptions.MySQLTimeoutException}; the application must
 *       return 503 and not leave open transactions that hold row locks themselves.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The exception injection translator targets the {@code java.sql.Statement} interface execute
 * family of methods. The Byte Buddy matcher uses {@code ElementMatchers.namedOneOf("execute",
 * "executeQuery", "executeUpdate")} on {@code Statement} implementors, catching all three variants
 * with a single instrumentation point. This covers batch execute variants too if the batch execute
 * internally delegates to single-statement execute (as most drivers do).
 *
 * <p>Spring's {@code SQLExceptionTranslator} uses the {@code SQLException}'s SQL state and error
 * code to classify the exception. To test a specific classification path, inject a subclass whose
 * SQL state matches the desired category: SQL state "08" for connection errors, "57" for
 * database-resource errors on DB2, or vendor-specific codes. The injected message can carry a
 * synthetic SQL state if the exception class exposes a {@code getSQLState()} method.
 *
 * <p>Hibernate's {@code SessionImpl#flush()} catches {@code HibernateException} but allows
 * {@code SQLException} to propagate (wrapped in a {@code JDBCException}); the persistence context
 * is then in an indeterminate state. Injecting {@code java.sql.SQLException} during flush
 * exercises the code paths that handle dirty session state, including whether the application
 * correctly evicts the session from the session factory's cache.
 *
 * <p>When combined with a probability modifier, sporadic statement failures simulate transient
 * database errors. Frameworks using Spring Retry will retry {@code TransientDataAccessException}
 * subtypes; ensure the retry does not reuse a connection that was left in a dirty state
 * (partially-written transaction) by the previous attempt.
 *
 * <p>The connection is not closed by this annotation; the pool receives it back via the normal
 * {@code close()} path in the transaction manager's finally block. If the application does not
 * close the connection on exception, the pool will eventually time out the leak — combine with
 * {@link ChaosJdbcConnectionAcquireDelay} to accelerate pool-leak detection.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcStatementExecuteInjectException(
 *     exceptionClassName = "java.sql.SQLTimeoutException",
 *     message = "query timeout exceeded")
 * class QueryTimeoutTest {
 *   @Test
 *   void applicationCountsTimeoutAndReturns503(ConnectionInfo info) {
 *     // assert query-timeout metric incremented and HTTP 503 returned
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
 */
@Repeatable(ChaosJdbcStatementExecuteInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_STATEMENT_EXECUTE)
public @interface ChaosJdbcStatementExecuteInjectException {

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
   * @ChaosJdbcStatementExecuteInjectException(id = "primary",  probability = 0.001)
   * @ChaosJdbcStatementExecuteInjectException(id = "replica",  probability = 0.01)
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
    ChaosJdbcStatementExecuteInjectException[] value();
  }
}
