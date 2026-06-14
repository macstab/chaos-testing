/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.testpack;

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
 * <p>Every {@code write()} call on paths under the target prefix fails with {@code ENOSPC} — no
 * space left on device. The fault fires on every matched write ({@code toxicity=1.0}), making the
 * disk-full condition immediately deterministic rather than probabilistic.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects an {@code IoRule.errno(path, WRITE, ENOSPC, 1.0)} via libchaos-io's {@code LD_PRELOAD}
 * interpose. In production, disk-full happens when a log volume, WAL partition, or data directory
 * fills up; Kubernetes PVCs running out of capacity are an especially common trigger.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Any path that writes to the affected prefix will fail immediately. Databases cannot commit
 * transactions, services cannot write logs, WAL cannot be flushed. Operator intervention (pruning
 * files or expanding the volume) is required to restore service.
 *
 * <h2>Industry references</h2>
 *
 * <p>Disk-full is one of the top five causes of database downtime in on-call post-mortems (Google
 * SRE Book, chapter 11). PostgreSQL emits a PANIC log and stops accepting connections; MySQL MyISAM
 * tables can corrupt when half-written. ENOSPC is described in {@code write(2)} POSIX.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosDiskFull(path = "/var/lib/postgresql")
 * class DiskFullResilienceTest {
 *
 *   @Test
 *   void databaseRefusesWritesGracefully(FilesystemChaos chaos, GenericContainer<?> db) {
 *     assertThatThrownBy(() -> db.execInContainer("psql", "-c", "INSERT INTO t VALUES (1)"))
 *         .hasMessageContaining("No space left");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosDiskFull.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.DiskFullComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosDiskFull {

  /**
   * Path prefix on which {@code write()} calls will fail with {@code ENOSPC}. Must be an absolute
   * path (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Probability that a matched write returns {@code ENOSPC}. {@code 1.0} (the default) makes the
   * fault deterministic — every write fails.
   */
  double toxicity() default 1.0;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosDiskFull[] value();
  }
}
