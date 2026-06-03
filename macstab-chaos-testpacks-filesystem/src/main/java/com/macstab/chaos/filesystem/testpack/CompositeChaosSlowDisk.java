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
 * <p>Both {@code read()} and {@code write()} on paths under the target prefix are delayed by
 * {@link #latencyMs()} milliseconds (default 200 ms) before libc is invoked. This models a
 * slow or saturated block device where every I/O operation incurs elevated service time.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies two libchaos-io rules: {@code IoRule.latency(path, READ, delay)} and
 * {@code IoRule.latency(path, WRITE, delay)}. In production, slow-disk symptoms appear during
 * NVMe queue depth saturation, HDD head-seek contention, EBS throughput throttling (when the
 * volume's IOPS/MBps burst budget is exhausted), or when a RAID array is degraded and is
 * rebuilding in the background.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * The disk is slow but functional. Applications remain alive but suffer from high I/O latency.
 * Connection pools may saturate waiting for slow queries; HTTP request timeouts can cascade. The
 * service recovers spontaneously once the I/O pressure is removed.
 *
 * <h2>Industry references</h2>
 *
 * <p>AWS EBS documentation defines throughput-burst limits for gp2 volumes; sustained I/O that
 * exceeds the burst bucket produces exactly this elevated latency pattern. Google's "Tail at Scale"
 * paper (Dean &amp; Barroso, CACM 2013) describes how a 200 ms disk tail latency on a single
 * replica can cause p99 degradation across an entire request fan-out. The Netflix Performance Team
 * uses disk latency injection as a primary tool for testing application timeout configurations.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosSlowDisk(path = "/data", latencyMs = 200)
 * class SlowDiskTest {
 *
 *   @Test
 *   void requestsStillCompleteWithinTimeout(SomeService svc) {
 *     assertThat(svc.fetchWithTimeout(Duration.ofSeconds(5))).isNotNull();
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSlowDisk.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.SlowDiskComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosSlowDisk {

  /**
   * Path prefix on which {@code read()} and {@code write()} are delayed. Must be an absolute path
   * (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Delay in milliseconds injected before each matched {@code read()} or {@code write()}.
   * Default {@code 200} ms models an EBS gp2 volume with burst credit exhaustion.
   */
  long latencyMs() default 200L;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosSlowDisk[] value();
  }
}
