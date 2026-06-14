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
 * <p>A fraction of {@code rename()} calls matching the source path prefix fail with {@code ENOENT}
 * — no such file or directory. The default probability is {@code 0.5}, simulating a race where the
 * source file disappears between the caller's {@code stat()} check and the actual {@code rename()}
 * system call.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects {@code IoRule.errno(path, RENAME_FROM, ENOENT, 0.5)} via libchaos-io's {@code
 * RENAME_FROM} operation. In production, atomic rename races occur in: write-ahead-log segment
 * rotation (producer renames the closed segment while the cleaner concurrently deletes it), SSTable
 * compaction in LSM-tree stores (RocksDB, LevelDB, Cassandra SSTables), and tmpfile + rename
 * patterns used by configuration management systems and package installers.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Individual rename operations fail but the application should catch {@code ENOENT} on rename and
 * retry or treat the absent source as already-completed (idempotency). Applications that do not
 * handle this case log errors, lose data, or corrupt their internal bookkeeping. Recovery is
 * spontaneous when the injected fault is removed.
 *
 * <h2>Industry references</h2>
 *
 * <p>POSIX specifies that {@code rename()} returns ENOENT when the old path does not exist. LSM
 * compaction atomicity via rename + ENOENT retry is described in the RocksDB Wiki (File Operations)
 * and the Cassandra CEP-7 architectural document. Apache Kafka's log segment compaction relies on
 * rename atomicity and explicitly handles ENOENT in its error path (KAFKA-6246).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosRenameRace(path = "/data/wal", toxicity = 0.5)
 * class RenameRaceTest {
 *
 *   @Test
 *   void walRotationHandlesEnoentGracefully(WalWriter wal) {
 *     assertThatCode(() -> wal.rotateClosed()).doesNotThrowAnyException();
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosRenameRace.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.RenameRaceComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosRenameRace {

  /**
   * Source-path prefix matched against the first argument of {@code rename()}. Must be an absolute
   * path (start with {@code /}). Defaults to {@code "*"} — wildcard matching every rename source.
   */
  String path() default "*";

  /**
   * Probability that a matched {@code rename()} source-path lookup returns {@code ENOENT}. Default
   * {@code 0.5} creates an observable but survivable rename-race condition.
   */
  double toxicity() default 0.5;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosRenameRace[] value();
  }
}
