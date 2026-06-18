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
 * Intercepts {@code DataSource.getConnection()} and throws the configured exception before a
 * connection is returned, simulating a total database unavailability or connection-pool exhaustion
 * scenario from the application's perspective.
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
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it immediately.
 *   <li>No connection is acquired from the pool; no database round-trip occurs; no pool slot is
 *       occupied by the failing call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every call to {@code getConnection()} throws the configured exception; in Spring
 *       applications this is typically translated by {@code SQLExceptionTranslator} into a {@code
 *       DataAccessException} subtype — assert the correct subtype is used.
 *   <li>Spring's {@code @Transactional} propagation: when {@code getConnection()} throws inside a
 *       transaction, the transaction manager rolls back immediately; assert that the rollback path
 *       does not itself call {@code getConnection()} (double-fault risk).
 *   <li>Inject {@code java.sql.SQLTransientConnectionException} to test transient-failure retry
 *       logic separately from {@code SQLNonTransientConnectionException} for permanent failures.
 *   <li><strong>Production failure mode:</strong> database certificate rotation causes all new
 *       connections to fail with {@code javax.net.ssl.SSLException} wrapped in {@code
 *       SQLException}; the application floods logs with stack traces, exhausts log storage, and the
 *       logging system drops critical alerts for unrelated services.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The exception injection translator targets {@code javax.sql.DataSource#getConnection()} at the
 * interface boundary, so HikariCP, DBCP2, and C3P0 are all affected regardless of their internal
 * pool implementation. The agent fires before any pool logic executes, meaning pool statistics
 * (active connections, pending acquisitions) are not disturbed — this is a clean injection that
 * does not pollute pool metrics.
 *
 * <p>HikariCP's pool health-check background thread also calls {@code getConnection()}
 * periodically; if this annotation is active during a health-check period, the health-check will
 * also fail, which may trigger pool eviction or connection-dead detection. This is often the
 * desired behavior: verify that the application detects pool health degradation and alerts on it.
 *
 * <p>The most common exception class for this annotation is {@code java.sql.SQLException} (the
 * default), which Spring's {@code SQLExceptionTranslator} will classify based on the SQL state. To
 * test a specific classification, inject a vendor-specific subclass such as {@code
 * com.mysql.cj.jdbc.exceptions.CommunicationsException} or use a standard JDK class with a SQL
 * state that maps to the desired Spring {@code DataAccessException} subtype.
 *
 * <p>When used with a probability modifier, the annotation produces intermittent connection
 * failures that exercise retry logic. Frameworks like Spring Retry that retry on {@code
 * TransientDataAccessException} will fire retries; assert that the retry count is bounded and that
 * each retry does not accumulate a new database connection that is never closed.
 *
 * <p>Unlike {@link ChaosJdbcConnectionAcquireDelay}, which adds latency but always returns a
 * connection, this annotation produces a hard failure that forces the application to take the error
 * path unconditionally. Combining both in separate test methods provides full coverage of the
 * slow-pool and dead-pool scenarios.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJdbcConnectionAcquireInjectException(
 *     exceptionClassName = "java.sql.SQLTransientConnectionException",
 *     message = "database unavailable")
 * class DatabaseUnavailableTest {
 *   @Test
 *   void applicationReturnsServiceUnavailable(ConnectionInfo info) {
 *     // assert HTTP 503 and DB-unavailable alert is triggered
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
 * @see ChaosJdbcConnectionAcquireDelay
 * @see ChaosJdbcStatementExecuteInjectException
 */
@Repeatable(ChaosJdbcConnectionAcquireInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JDBC,
    operationType = OperationType.JDBC_CONNECTION_ACQUIRE)
public @interface ChaosJdbcConnectionAcquireInjectException {

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
   * @ChaosJdbcConnectionAcquireInjectException(id = "primary",  probability = 0.001)
   * @ChaosJdbcConnectionAcquireInjectException(id = "replica",  probability = 0.01)
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
    ChaosJdbcConnectionAcquireInjectException[] value();
  }
}
