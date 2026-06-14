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
 * <p>A tiny fraction of {@code write()} calls on the target path prefix succeed but silently write
 * fewer bytes than requested — a torn write. The default probability is {@code 0.001} (1 in 1000
 * writes) to model the extremely rare but real occurrence of silent partial writes on storage
 * hardware that mis-reports completion.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects {@code IoRule.torn(path, WRITE, 0.001)} via libchaos-io. The {@code TORN} effect
 * truncates the byte count returned to userspace: libc is called, succeeds, but the application is
 * told fewer bytes were written than actually were. In production, torn writes occur when: a power
 * failure interrupts a write mid-sector (especially on HDDs without capacitor-backed write caches),
 * an NVMe controller resets mid-command, or a virtualised storage backend flushes only part of a
 * large I/O before a live migration snapshot.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * The application believes fewer bytes were persisted than it intended. If it does not re-issue the
 * remainder of the write (partial-write loop) the file is silently truncated mid-record. WAL
 * corruption, SSTable header truncation, and index file corruption are common consequences. Without
 * page-level checksums, the corruption is invisible until read-back; with checksums it surfaces as
 * a checksum mismatch long after the write epoch has closed.
 *
 * <h2>Industry references</h2>
 *
 * <p>The POSIX specification for {@code write(2)} permits partial writes ("If a write request is
 * for more than {PIPE_BUF} bytes…"); applications MUST loop on partial-write returns. PostgreSQL's
 * full-page write (FPW) mechanism exists specifically because torn-write is a documented failure
 * mode for WAL. The SQLite developers describe torn-write detection in the journal-mode design
 * document. AWS documented NVMe controller reset torn-write behaviour in an EC2 instance-storage
 * advisory (2020).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosWriteCorruption(path = "/data/wal", toxicity = 0.001)
 * class WriteTornTest {
 *
 *   @Test
 *   void walWriterHandlesPartialWriteCorrectly(WalWriter wal) {
 *     wal.write(LARGE_PAYLOAD);
 *     assertThat(wal.verify()).isTrue(); // WAL must detect and repair torn writes
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosWriteCorruption.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.WriteCorruptionComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosWriteCorruption {

  /**
   * Path prefix on which {@code write()} torn-write corruption is applied. Must be an absolute path
   * (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Probability that a matched write is torn (returns a short byte count). Very low values (default
   * {@code 0.001} = 0.1%) match real-world hardware torn-write rates.
   */
  double toxicity() default 0.001;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosWriteCorruption[] value();
  }
}
