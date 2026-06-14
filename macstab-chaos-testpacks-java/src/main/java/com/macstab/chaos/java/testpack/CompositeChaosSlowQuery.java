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
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Delays every {@code Connection.commit()} call by {@link #commitDelayMs()} milliseconds,
 * simulating a database that takes a long time to flush dirty pages and confirm a transaction — the
 * Java-layer equivalent of slow WAL sync on a loaded PostgreSQL or MySQL server.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code java.sql.Connection#commit()} via the JVM chaos agent and injects a
 * deterministic delay. In production, slow commits occur when the database is under I/O pressure
 * (WAL full, disk saturated, replication lag causing synchronous standby to be slow).
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Transactions that commit slowly hold their connections longer, compounding with connection-pool
 * pressure. ORM statement timeouts shorter than the commit delay will abort the transaction.
 * Retries may cause duplicate writes if the application does not check the commit result.
 *
 * <h2>Industry references</h2>
 *
 * <p>PostgreSQL documentation §"Write-Ahead Logging" explains the fsync-on-commit path that makes
 * commit latency disk-bound. pg_stat_activity's {@code wait_event = 'WALWriteLock'} identifies
 * sessions blocked in commit.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosSlowQuery(commitDelayMs = 2000)
 * class SlowQueryTest {
 *   @Test
 *   void transactionTimeoutFiresOnSlowCommit(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSlowQuery.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.SlowQueryComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosSlowQuery {

  /**
   * Delay injected before each {@code commit()} returns, in milliseconds.
   *
   * @return delay in ms; default 2000
   */
  long commitDelayMs() default 2_000L;

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
    CompositeChaosSlowQuery[] value();
  }
}
