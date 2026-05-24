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
 * Intercepts {@code Connection.rollback()} and throws the configured exception before the rollback
 * command is sent to the database, simulating a failed rollback that leaves the connection in an
 * unknown state and exercises the most deeply nested error-recovery paths in JDBC-using frameworks.
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
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it immediately.
 *   <li>No rollback is sent to the database; the connection's state is indeterminate; row-level
 *       locks may remain held until the database detects the broken connection and rolls back
 *       automatically (typically via TCP keepalive timeout or idle connection timeout).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The rollback itself throws an exception; Spring's {@code DataSourceTransactionManager}
 *       catches it in the rollback path and — depending on version — may swallow it or re-throw it
 *       as {@code TransactionSystemException}; assert which behaviour the application relies on.
 *   <li>HikariCP will evict the connection from the pool when rollback fails (the pool calls {@code
 *       isValid()} on exception and evicts the connection if the check fails); assert that the pool
 *       size shrinks and that the pool replenishes correctly.
 *   <li>The original business exception (which triggered the rollback) may be masked by the
 *       rollback exception; assert that error logging captures both the business exception and the
 *       rollback exception so operators can diagnose the root cause.
 *   <li><strong>Production failure mode:</strong> a database reboot causes all open connections to
 *       drop; the application's exception handlers call rollback on broken connections, the
 *       rollback throws {@code SQLException: connection closed}, the pool evicts all connections
 *       simultaneously, and the pool's connection-creation logic hammers the restarting database
 *       with reconnect attempts — a reconnect storm layered on top of the original restart.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>A rollback failure is exceptional in production because rollback is called in error-handling
 * paths that already assume something went wrong. Most application code does not have a try-catch
 * around rollback; a thrown exception here propagates up through the transaction manager's finally
 * block, potentially masking the original exception that caused the rollback. Java's exception
 * masking rule means the original exception is attached as a suppressed exception, not as the cause
 * — verify that the application's logging or error-tracking tool surfaces both.
 *
 * <p>Spring's {@code DataSourceTransactionManager.doRollback()} wraps the rollback in a try-catch
 * and calls {@code triggerAfterCompletion(status, ROLLBACK_FAILED)} on all registered
 * synchronizations. A failing rollback means the database connection is in an unknown state; Spring
 * marks the connection as damaged and calls {@code DataSourceUtils.releaseConnection()}, which asks
 * the pool to close (not return) the connection. HikariCP then removes the connection from its
 * internal bag and schedules pool replenishment.
 *
 * <p>Hibernate's {@code SessionImpl}: if rollback fails, Hibernate does not attempt to re-use the
 * session. The {@code Session} is closed in its finally block regardless of rollback success; the
 * {@code EntityManager} is invalidated. Any subsequent call to the same {@code EntityManager}
 * throws {@code IllegalStateException: Session is closed}.
 *
 * <p>This annotation is most useful combined with {@link ChaosJdbcTransactionCommitInjectException}
 * in a single test class using different probabilities: some transactions fail at commit, some also
 * fail at rollback, creating a full matrix of transaction lifecycle failure scenarios that expose
 * gaps in exception-handling coverage.
 *
 * <p>The most realistic injected exception class is {@code java.sql.SQLRecoverableException}, which
 * is what most JDBC drivers throw when the connection is lost. Using {@code
 * java.sql.SQLNonTransientException} tests the path where the application gives up rather than
 * retrying.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcTransactionRollbackInjectException(
 *     exceptionClassName = "java.sql.SQLRecoverableException",
 *     message = "connection lost during rollback")
 * class RollbackFailureTest {
 *   @Test
 *   void bothExceptionsAreLoggedAndPoolReplenishes(ConnectionInfo info) {
 *     // assert original exception and rollback exception both appear in logs
 *     // assert pool size returns to minimum after eviction
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
 * @see ChaosJdbcTransactionRollbackDelay
 * @see ChaosJdbcTransactionCommitInjectException
 */
@Repeatable(ChaosJdbcTransactionRollbackInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_TRANSACTION_ROLLBACK)
public @interface ChaosJdbcTransactionRollbackInjectException {

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
   * @ChaosJdbcTransactionRollbackInjectException(id = "primary",  probability = 0.001)
   * @ChaosJdbcTransactionRollbackInjectException(id = "replica",  probability = 0.01)
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
    ChaosJdbcTransactionRollbackInjectException[] value();
  }
}
