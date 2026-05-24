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
 * Intercepts {@code Connection.commit()} and throws the configured exception before the transaction
 * is written to the database, simulating a commit failure that leaves the application's
 * transactional state ambiguous — the most dangerous JDBC failure mode.
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
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it immediately.
 *   <li>No commit is sent to the database; the transaction's changes are not persisted; row-level
 *       locks are released when the connection is eventually closed or rolled back.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every commit throws the configured exception; Spring's {@code DataSourceTransactionManager}
 *       translates it to {@code TransactionSystemException} — assert this exception type propagates
 *       correctly to the caller and is not silently swallowed.
 *   <li>The application must not assume data was persisted after a failed commit; assert that the
 *       application does not return HTTP 200 when the commit failed.
 *   <li>Event publishing after commit ({@code @TransactionalEventListener(AFTER_COMMIT)}) must not
 *       fire when commit throws; assert that downstream systems do not receive events for
 *       uncommitted data.
 *   <li><strong>Production failure mode:</strong> a network interruption during commit causes
 *       {@code SQLException: connection reset}; the application logs the error and returns HTTP
 *       500, but the database may or may not have committed (TCP packet was in-flight when reset
 *       occurred); this is the two-generals problem in database commits — inject to verify the
 *       application handles the ambiguity without serving stale reads to subsequent requests.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>A commit failure is the most dangerous JDBC failure mode because the application cannot know
 * whether the commit succeeded or not from the JDBC exception alone. If the network drops after the
 * database has written the transaction but before the ACK reaches the driver, the driver throws
 * {@code SQLException} with a "connection reset" message while the database considers the
 * transaction committed. This annotation injects that ambiguity synthetically, allowing tests to
 * verify that the application handles it correctly (e.g. by re-reading the committed data, using
 * idempotency keys, or surfacing the ambiguity to the caller).
 *
 * <p>Spring's commit failure handling: {@code DataSourceTransactionManager.doCommit()} catches
 * {@code SQLException} and wraps it in {@code TransactionSystemException}. The original
 * {@code @Transactional} method's return value has already been computed by this point; the
 * exception is thrown from the proxy's commit phase, not from the business method. Callers of the
 * {@code @Transactional} method will see {@code TransactionSystemException} on their call stack
 * even though the business logic completed successfully.
 *
 * <p>JPA / Hibernate: {@code EntityTransaction.commit()} delegates to the JDBC connection's commit.
 * A failure here causes Hibernate to mark the {@code EntityManager} as closed; subsequent
 * operations on the same {@code EntityManager} throw {@code IllegalStateException: Session is
 * closed}. Tests should verify that the application does not attempt to reuse the entity manager
 * after a commit failure.
 *
 * <p>Outbox pattern: applications writing to a transactional outbox (events in the same transaction
 * as the state change) lose both the state change and the outbox entry if commit fails. The
 * commit-failure test must assert that the outbox is empty after the fault fires, and that the
 * event is not published to the downstream broker.
 *
 * <p>Injecting {@code java.sql.SQLRecoverableException} tests the path where the application
 * attempts to reconnect and retry; injecting {@code java.sql.SQLNonTransientException} tests the
 * path where the application gives up and alerts an operator.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcTransactionCommitInjectException(
 *     exceptionClassName = "java.sql.SQLRecoverableException",
 *     message = "connection reset during commit")
 * class CommitFailureTest {
 *   @Test
 *   void applicationReturns500AndOutboxIsEmpty(ConnectionInfo info) {
 *     // assert HTTP 500 and no events published to broker
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
 * @see ChaosJdbcTransactionCommitDelay
 * @see ChaosJdbcTransactionRollbackInjectException
 */
@Repeatable(ChaosJdbcTransactionCommitInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_TRANSACTION_COMMIT)
public @interface ChaosJdbcTransactionCommitInjectException {

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
   * @ChaosJdbcTransactionCommitInjectException(id = "primary",  probability = 0.001)
   * @ChaosJdbcTransactionCommitInjectException(id = "replica",  probability = 0.01)
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
    ChaosJdbcTransactionCommitInjectException[] value();
  }
}
