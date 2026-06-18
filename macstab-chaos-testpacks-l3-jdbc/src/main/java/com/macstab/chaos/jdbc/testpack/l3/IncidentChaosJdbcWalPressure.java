/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates WAL (Write-Ahead Log) fsync pressure causing commit timeouts and replica lag
 * accumulation: slow fsync and write operations on the database data directory combine with network
 * receive latency to model an overloaded storage subsystem during heavy write workloads.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Filesystem: FSYNC latency of {@code fsyncDelayMs} ms on {@code dataPath} — delays WAL
 *       segment flushing, causing commits to block until timeout
 *   <li>Filesystem: WRITE latency of {@code fsyncDelayMs / 2} ms on {@code dataPath} — models
 *       saturated write throughput slowing page writes alongside WAL
 *   <li>Connection: RECV latency of {@code fsyncDelayMs} ms at toxicity 1.0 — simulates back-
 *       pressure from the database as it falls behind under write load
 *   <li>JVM: {@code injectException("java.sql.SQLTimeoutException", "WAL sync timeout exceeded")}
 *       on classes matching {@code classPattern} at METHOD_ENTER — surfaces commit timeout at the
 *       Java layer
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Commit latency spikes cause application-level timeouts, replica lag accumulates, and read
 * replicas fall progressively behind; operator intervention to tune or replace the storage
 * subsystem is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>WAL fsync delay causing commit timeout and replica lag is documented in the PostgreSQL
 * documentation §"WAL Configuration", the PostgreSQL wiki "Tuning Your PostgreSQL Server", and
 * post-mortems from heavy OLTP workloads describing how slow storage devices cause commit storms.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.IO, LibchaosLib.NET})
 * @IncidentChaosJdbcWalPressure(fsyncDelayMs = 800L, dataPath = "/var/lib/postgresql")
 * class JdbcWalPressureTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJdbcWalPressure.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jdbc.testpack.l3.composers.JdbcWalPressureComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosJdbcWalPressure {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Milliseconds of latency injected into FSYNC and WRITE syscalls on the data path. */
  long fsyncDelayMs() default 500L;

  /** Absolute path prefix for the database data directory to target with I/O latency. */
  String dataPath() default "/var/lib/postgresql";

  /** Class name prefix used to match JDBC client methods for exception injection. */
  String classPattern() default "jdbc";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJdbcWalPressure[] value();
  }
}
