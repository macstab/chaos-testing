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
 * <p>All write-path syscalls ({@code write()}, {@code rename()}, {@code unlink()}) on paths under
 * the target prefix are failed with {@code EACCES} — permission denied. This simulates the
 * application-visible effect of a filesystem being remounted read-only by the kernel following
 * media error detection. The fault fires on every matched call ({@code toxicity=1.0}).
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies three libchaos-io rules: {@code errno(path, WRITE, EACCES, 1.0)}, {@code errno(path,
 * RENAME_FROM, EACCES, 1.0)}, and {@code errno(path, UNLINK, EACCES, 1.0)}. In production, the
 * Linux kernel remounts a filesystem read-only upon detecting an unrecoverable I/O error (e.g. an
 * NVMe controller reset or a dm-multipath path-loss with no remaining paths), or when the block
 * device is forcibly write-protected by a hypervisor during live migration.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Applications that cannot write to their data directory are effectively dead. Databases refuse all
 * transactions; log pipelines drop records; WAL rotation fails. Operator intervention is required
 * to remount the filesystem read-write after the underlying block device is healthy.
 *
 * <h2>Industry references</h2>
 *
 * <p>Linux ext4 and xfs remount-on-error behaviour is documented in {@code mount(8)} and each
 * filesystem's {@code errors=remount-ro} option. GCE Persistent Disk and AWS EBS both expose this
 * remount-ro behaviour during controller reset; Kubernetes CSI drivers have dedicated Prometheus
 * metrics for read-only volume events. EACCES on writes is the POSIX-specified result for a
 * read-only filesystem write attempt (POSIX.1-2017, {@code write(2)}).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosReadOnlyFilesystem(path = "/var/lib/postgresql")
 * class ReadOnlyFilesystemTest {
 *
 *   @Test
 *   void databaseRejectsAllWrites(DataSource ds) {
 *     assertThatThrownBy(() -> ds.getConnection().prepareStatement("INSERT INTO t VALUES (1)").execute())
 *         .hasRootCauseInstanceOf(java.sql.SQLException.class);
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosReadOnlyFilesystem.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.ReadOnlyFilesystemComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosReadOnlyFilesystem {

  /**
   * Path prefix to render read-only. Must be an absolute path (start with {@code /}). Defaults to
   * {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Probability that each matched write/rename/unlink call returns {@code EACCES}. {@code 1.0} (the
   * default) makes the read-only condition deterministic.
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
    CompositeChaosReadOnlyFilesystem[] value();
  }
}
