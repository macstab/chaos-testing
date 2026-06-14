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
 * <p>A fraction of {@code open()} calls on the target path prefix fail with {@code EIO} —
 * simulating inode or filesystem-metadata read failure. The default probability is {@code 0.3} (30
 * % of opens fail), modelling a filesystem where metadata structures are damaged or where the
 * underlying block device returns errors specifically on metadata blocks.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects {@code IoRule.errno(path, OPEN, EIO, 0.3)} via libchaos-io. Filesystem metadata lives
 * separately from file data in the inode table and directory tree. When the block device returns
 * EIO reading an inode, the kernel propagates EIO to the {@code open()} caller — the application
 * cannot determine whether the file exists at all, let alone read its data. In production this
 * happens during journal replay after an unclean shutdown, during fsck on a partially-failed RAID
 * member, or on a flash device with a worn-out inode region.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Not every open fails; the filesystem is partially operational. Applications that retry
 * successfully on a second attempt will recover. Applications that open files inside hot loops
 * (e.g. configuration reload, plugin discovery) will see intermittent EIO errors that are difficult
 * to attribute without good logging. 30 % failure rate is high enough to produce consistent test
 * signal without total service outage.
 *
 * <h2>Industry references</h2>
 *
 * <p>Linux ext4 and xfs journal recovery can produce EIO on inode reads if journal blocks are
 * damaged (see {@code e2fsck(8)} and the ext4 design document). Btrfs metadata tree reads return
 * EIO on checksum mismatch ({@code btrfs check --check-data-csum}). AWS EBS volumes in "impaired"
 * state produce sporadic EIO on metadata-heavy workloads before full error state is declared (AWS
 * EBS Volume Status Checks documentation).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosMetadataFailure(path = "/etc/app/config", toxicity = 0.3)
 * class MetadataFailureTest {
 *
 *   @Test
 *   void configReloadRetriesOnEio(ConfigService svc) {
 *     assertThatCode(() -> svc.reloadConfig()).doesNotThrowAnyException();
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosMetadataFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.MetadataFailureComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosMetadataFailure {

  /**
   * Path prefix on which {@code open()} calls may fail with {@code EIO}. Must be an absolute path
   * (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Probability that a matched {@code open()} returns {@code EIO}. Default {@code 0.3} models a
   * partially-degraded filesystem where roughly 30 % of inode reads fail.
   */
  double toxicity() default 0.3;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosMetadataFailure[] value();
  }
}
