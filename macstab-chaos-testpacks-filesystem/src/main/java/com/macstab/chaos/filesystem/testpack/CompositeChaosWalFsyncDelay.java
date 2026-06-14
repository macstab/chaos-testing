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
 * <p>Every {@code fsync()} and {@code fdatasync()} call on the target path prefix is delayed by
 * {@link #latencyMs()} milliseconds (default 500 ms). The delay is applied before libc is invoked,
 * so the durability barrier is still honoured — just very slowly.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects an {@code IoRule.latency(path, FSYNC, Duration.ofMillis(latencyMs))} via libchaos-io.
 * In production, WAL fsync latency spikes during NVMe I/O saturation, when EBS volume burst credits
 * are exhausted (AWS), or when a storage controller queue is overwhelmed. PostgreSQL and MySQL both
 * measure commit latency via fsync round-trip time; a 500 ms delay directly translates into
 * transaction throughput collapse.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * The service is still up and commits are still durable, but throughput collapses because every
 * write transaction must wait for the durability barrier. Queries time out, connection pools
 * saturate, and downstream services observe elevated latency. Recovery is spontaneous when the
 * underlying disk pressure is removed.
 *
 * <h2>Industry references</h2>
 *
 * <p>PostgreSQL's {@code checkpoint_completion_target} and {@code wal_sync_method} settings exist
 * precisely because fsync latency is a primary TPS governor. The 2021 Jepsen analysis of RDS
 * PostgreSQL identified WAL fsync delays as a key factor in linearizability violations under
 * network partition. AWS EBS burst credit exhaustion is documented as a common production fsync
 * latency trigger (AWS EBS Performance docs).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosWalFsyncDelay(path = "/var/lib/postgresql/wal", latencyMs = 500)
 * class WalFsyncDelayTest {
 *
 *   @Test
 *   void transactionThroughputDegradesBelowThreshold(DataSource ds) {
 *     long tps = measureTps(ds, Duration.ofSeconds(10));
 *     assertThat(tps).isLessThan(100); // expect significant throughput collapse
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosWalFsyncDelay.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.WalFsyncDelayComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosWalFsyncDelay {

  /**
   * Path prefix on which {@code fsync()} and {@code fdatasync()} are delayed. Must be an absolute
   * path (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Delay in milliseconds injected before each matched {@code fsync()} or {@code fdatasync()}.
   * Default {@code 500} ms models an EBS burst-credit-exhausted volume.
   */
  long latencyMs() default 500L;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosWalFsyncDelay[] value();
  }
}
