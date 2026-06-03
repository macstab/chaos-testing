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
 * <p>Delays every {@code DataSource.getConnection()} call by {@link #acquireDelayMs()} milliseconds,
 * simulating an exhausted or very slow JDBC connection pool where every connection-acquire waits
 * at the pool's maximum timeout before succeeding or failing.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code javax.sql.DataSource#getConnection()} via the JVM chaos agent and injects
 * a deterministic delay before control returns to the caller. In production, connection-pool
 * exhaustion occurs when application threads hold connections for too long (open transactions,
 * long-running queries), leaving none available for incoming requests.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Pool timeouts shorter than {@code acquireDelayMs} will fire on every request. The application
 * converts these into errors, health checks fail, and the instance may be removed from the load
 * balancer. Operator intervention or connection-pool tuning is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>HikariCP documentation §"Pool sizing" describes why pool exhaustion causes cascading
 * failures. The "connection timeout" anti-pattern (too-long pool wait disguised as latency) is
 * described in the Percona blog "Diagnosing Connection Pool Exhaustion in MySQL".
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosConnectionPoolExhaustion(acquireDelayMs = 5000)
 * class PoolExhaustionTest {
 *   @Test
 *   void applicationReturns503OnPoolTimeout(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosConnectionPoolExhaustion.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ConnectionPoolExhaustionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosConnectionPoolExhaustion {

  /**
   * Delay injected before each {@code getConnection()} returns, in milliseconds.
   *
   * @return delay in ms; default 5000
   */
  long acquireDelayMs() default 5_000L;

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
    CompositeChaosConnectionPoolExhaustion[] value();
  }
}
