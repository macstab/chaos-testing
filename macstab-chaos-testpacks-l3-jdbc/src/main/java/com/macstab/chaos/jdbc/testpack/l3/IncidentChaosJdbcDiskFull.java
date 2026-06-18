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
 * <p>Simulates a disk-full condition on the database data directory during bulk writes or
 * migrations: ENOSPC errors on both WRITE and FSYNC operations cause transactions to fail
 * mid-execution with potential for partial data and corruption risk.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Filesystem: WRITE → ENOSPC at {@code probability} on {@code path} — bulk inserts and WAL
 *       writes fail with "no space left on device"
 *   <li>Filesystem: FSYNC → ENOSPC at {@code probability} on {@code path} — commit flushes fail,
 *       causing transactions to be rolled back or left in indeterminate state
 *   <li>JVM: {@code injectException("java.sql.SQLException", "disk full — write failed")} on
 *       classes matching {@code classPattern} at METHOD_ENTER — surfaces disk-full state at the
 *       Java layer before the write even reaches the filesystem
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Partial data persisted before ENOSPC leaves tables in an inconsistent state; WAL corruption risk
 * exists if FSYNC fails mid-segment; operator intervention to expand the volume or prune files is
 * required to recover.
 *
 * <h2>Industry references</h2>
 *
 * <p>Disk-full during bulk write and migration is a documented operational failure mode: described
 * in the PostgreSQL documentation §"Monitoring Disk Usage", the MySQL documentation §"Disk Full in
 * MySQL", and post-mortems from data-migration incidents where unexpected data volume growth
 * exhausted provisioned storage.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.IO})
 * @IncidentChaosJdbcDiskFull(path = "/var/lib/postgresql", probability = 0.95)
 * class JdbcDiskFullTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJdbcDiskFull.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jdbc.testpack.l3.composers.JdbcDiskFullComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosJdbcDiskFull {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Absolute path prefix for the database data directory to target with ENOSPC faults. */
  String path() default "/var/lib/postgresql";

  /** Fraction of WRITE and FSYNC syscalls that return ENOSPC (0.0–1.0). */
  double probability() default 0.9;

  /** Class name prefix used to match JDBC client methods for exception injection. */
  String classPattern() default "jdbc";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJdbcDiskFull[] value();
  }
}
