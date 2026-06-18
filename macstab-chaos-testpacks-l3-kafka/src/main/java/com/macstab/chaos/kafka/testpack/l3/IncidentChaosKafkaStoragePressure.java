/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a Kafka broker disk filling: log segment writes slow down, fsync of segment files
 * returns EIO to prevent data corruption, and produce requests back off as the broker cannot
 * acknowledge batches — ultimately causing consumers to rebalance as producers block.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Filesystem: WRITE latency of {@code latencyMs} ms on path {@code path} — log segment
 *       appends slow down; broker append throughput falls and produce latency rises
 *   <li>Filesystem: FSYNC → EIO at probability {@code probability} on path {@code path} — segment
 *       cleanup and index fsync fail; unclean segment closure triggers recovery on restart
 *   <li>Connection: SEND latency of {@code latencyMs/2} ms — produce requests are delayed from the
 *       network layer, compounding the storage-side backpressure seen by producers
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Broker disk fills cause log segment cleanup lag, which increases retention beyond configured
 * limits; producers see backpressure; consumers rebalance as produce blocks propagate through the
 * pipeline.
 *
 * <h2>Industry references</h2>
 *
 * <p>Broker disk fills → log segment cleanup lag → producer backpressure → consumer rebalance is a
 * documented Kafka operational hazard. Multiple post-mortems cite unmonitored disk growth from
 * high-retention topics as the root cause.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.IO, LibchaosLib.NET})
 * @IncidentChaosKafkaStoragePressure(path = "/var/kafka/data", latencyMs = 500L)
 * class KafkaStoragePressureTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosKafkaStoragePressure.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.kafka.testpack.l3.composers.KafkaStoragePressureComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosKafkaStoragePressure {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Absolute path prefix of the Kafka data directory to apply filesystem rules to. */
  String path() default "/var/kafka/data";

  /** Milliseconds of write latency injected into log segment appends. */
  long latencyMs() default 300L;

  /** Probability (0.0–1.0) that an fsync on the data path returns EIO. */
  double probability() default 0.3;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosKafkaStoragePressure[] value();
  }
}
