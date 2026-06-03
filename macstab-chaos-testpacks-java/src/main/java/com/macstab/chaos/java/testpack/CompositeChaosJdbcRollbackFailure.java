/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Injects a {@code SQLException} into {@code Connection.rollback()} with probability
 * {@link #probability()}, simulating a database that has lost the connection or encountered an
 * internal error precisely when the application attempts to undo a failed transaction.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code java.sql.Connection#rollback()} via the JVM chaos agent and injects a
 * {@code java.sql.SQLException} at the configured probability. In production, rollback failures
 * occur on connection reset, network partition, or database crash during an in-flight transaction.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * A failed rollback leaves the transaction in an indeterminate state. Application code that
 * swallows the rollback exception will silently leave partial writes outstanding. Frameworks that
 * re-throw the rollback exception on top of the original exception make root-cause analysis
 * difficult. This is one of the most underappreciated failure modes in JDBC programming.
 *
 * <h2>Industry references</h2>
 *
 * <p>The JDBC specification (JSR-221) §11.4 states that {@code rollback()} may throw
 * {@code SQLException}. Spring's {@code DataSourceTransactionManager} swallows it with a warning
 * log — see {@code DataSourceTransactionManager#doRollback}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosJdbcRollbackFailure(probability = 0.5)
 * class RollbackFailureTest {
 *   @Test
 *   void applicationHandlesRollbackExceptionCorrectly(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosJdbcRollbackFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.JdbcRollbackFailureComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosJdbcRollbackFailure {

  /**
   * Probability in {@code (0.0, 1.0]} that a {@code rollback()} call throws {@code SQLException}.
   *
   * @return probability; default 0.5
   */
  double probability() default 0.5;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosJdbcRollbackFailure[] value();
  }
}
