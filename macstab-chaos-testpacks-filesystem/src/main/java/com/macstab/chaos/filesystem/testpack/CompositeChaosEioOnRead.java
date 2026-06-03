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
 * <h2>What this is</h2>
 *
 * <p>A fraction of {@code read()} calls on paths under the target prefix fail with {@code EIO} —
 * input/output error. The probability defaults to {@code 0.5} (50 % of reads fail), modelling
 * a partially-degraded storage device where some sectors are still readable but others are not.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects {@code IoRule.errno(path, READ, EIO, 0.5)} via libchaos-io. In production, EIO on
 * reads is produced by: bad sectors on spinning disk (HDD SMART reallocated sector event), an
 * NVMe flash-cell wearing out, a SAN fabric transient, or an iSCSI multipath failure leaving only
 * a degraded path. Unlike ENOSPC (no space) the EIO error is spatial — some reads succeed, some
 * do not, making it far harder to detect without systematic retry coverage.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * EIO on reads means the application cannot reliably recover data from disk. 50 % failure
 * probability means caches and retries offer only partial relief. Databases with page-level
 * checksums will detect corruption and refuse to return the bad page (PostgreSQL PANIC); those
 * without checksums may silently return garbage. Operator investigation and potentially data
 * restoration from backup is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>The POSIX specification for {@code read(2)} lists EIO as the return value for a physical
 * I/O error. AWS EBS surface EIO when a volume enters the "error" state during a host migration
 * stall. Google Colossus design principles explicitly budget for per-chunk EIO during disk fleet
 * replacement cycles (Google Cloud Storage reliability whitepaper, 2022).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosEioOnRead(path = "/data/segments", toxicity = 0.5)
 * class EioOnReadTest {
 *
 *   @Test
 *   void readerRetriesOrFailsFastWithClearError(StorageService svc) {
 *     assertThatThrownBy(() -> svc.readSegment("seg-001"))
 *         .hasMessageContaining("I/O error");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosEioOnRead.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.EioOnReadComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosEioOnRead {

  /**
   * Path prefix on which {@code read()} calls may fail with {@code EIO}. Must be an absolute path
   * (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Probability that a matched read returns {@code EIO}. Default {@code 0.5} models a
   * partially-degraded storage device where roughly half of sector reads fail.
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
    CompositeChaosEioOnRead[] value();
  }
}
