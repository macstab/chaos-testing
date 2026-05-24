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
 * Intercepts {@code PreparedStatement.execute()} / {@code executeQuery()} / {@code executeUpdate()}
 * and throws the configured exception before parameterised SQL is sent, enabling tests to verify
 * ORM and Spring Data error-handling paths for specific JDBC failure types that occur during
 * parameterised query execution.
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
 *   <li>Before every call to {@code java.sql.PreparedStatement#execute()}, {@code executeQuery()},
 *       or {@code executeUpdate()} inside the target container's JVM, the chaos agent intercepts
 *       the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it immediately.
 *   <li>No SQL is sent; the prepared statement's parameter bindings are discarded; the connection
 *       remains valid and is returned to the pool by the exception handler.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every parameterised SQL execution throws the configured exception; Hibernate wraps it in a
 *       {@code JDBCException} subtype — assert the specific subtype ({@code
 *       JDBCConnectionException}, {@code QueryTimeoutException}, etc.) to verify correct wrapping.
 *   <li>Spring Data JPA methods that call {@code save()} in a {@code @Transactional} context: the
 *       exception propagates through the flush, the transaction is rolled back, and the entity
 *       manager is invalidated — assert the entity manager is not reused after rollback.
 *   <li>Inject {@code java.sql.SQLIntegrityConstraintViolationException} to test constraint
 *       violation handling (duplicate key, foreign-key violation) without needing to set up
 *       conflicting data in the database.
 *   <li><strong>Production failure mode:</strong> a database replica lag causes read queries to
 *       return stale data, and a concurrent write to the primary violates a unique constraint; the
 *       application receives {@code SQLIntegrityConstraintViolationException} and must decide
 *       whether to retry with a new key or return a conflict error to the user.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets the no-argument execute methods on {@code PreparedStatement}
 * implementations. In Hibernate's flush pipeline, each dirty entity generates one or more {@code
 * PreparedStatement.executeUpdate()} calls. When this annotation fires, Hibernate's {@code
 * BatchingBatcher} or {@code NonBatchingBatcher} catches the exception and wraps it in a {@code
 * JDBCException}. The {@code Session} is then marked as closed/invalid by Hibernate's exception
 * handling contract, which means all subsequent operations on the same session will throw {@code
 * IllegalStateException}.
 *
 * <p>Spring's {@code SQLExceptionTranslator} chain (vendor-specific → SQL state → fallback) is
 * exercised by the injected exception's SQL state field. If the injected class is a plain {@code
 * SQLException} without a SQL state, the translator falls back to a generic {@code
 * UncategorizedSQLException}. To test a specific translation, use a subclass that provides the
 * appropriate SQL state in its constructor.
 *
 * <p>Optimistic locking in JPA is implemented via a versioned {@code UPDATE} that checks the
 * current version column; if the update affects 0 rows, JPA throws {@code OptimisticLockException}.
 * Injecting {@code SQLException} before the update fires tests the code path where the version
 * check itself fails due to a database error, which is a distinct scenario from the normal
 * optimistic lock conflict path.
 *
 * <p>When combined with a probability modifier, sporadic injection tests transactional retry
 * behaviour. A common bug is retrying a transaction that had partially flushed dirty entities
 * before the injected exception; the retry may re-insert already-inserted entities, violating
 * unique constraints. Inject at probability 0.5 to expose this class of bug.
 *
 * <p>The connection is not invalidated by this annotation; the driver's connection remains in the
 * pool. However, if the application does not close the connection after the exception (e.g. leaks a
 * connection from a manually managed resource), the injected fault makes the leak observable
 * through pool statistics.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcPreparedStatementInjectException(
 *     exceptionClassName = "java.sql.SQLIntegrityConstraintViolationException",
 *     message = "duplicate key value violates unique constraint")
 * class UniqueConstraintViolationTest {
 *   @Test
 *   void applicationReturns409Conflict(ConnectionInfo info) {
 *     // assert HTTP 409 and no open transactions left
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
 * @see ChaosJdbcPreparedStatementDelay
 * @see ChaosJdbcStatementExecuteInjectException
 */
@Repeatable(ChaosJdbcPreparedStatementInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_PREPARED_STATEMENT)
public @interface ChaosJdbcPreparedStatementInjectException {

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
   * @ChaosJdbcPreparedStatementInjectException(id = "primary",  probability = 0.001)
   * @ChaosJdbcPreparedStatementInjectException(id = "replica",  probability = 0.01)
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
    ChaosJdbcPreparedStatementInjectException[] value();
  }
}
