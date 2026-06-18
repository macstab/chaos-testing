/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.kafka.testpack.l3.IncidentChaosKafkaStoragePressure;

/**
 * Composer for {@link IncidentChaosKafkaStoragePressure}.
 *
 * <p>Slows log segment writes, injects EIO on fsync to simulate segment cleanup failure, and adds
 * SEND latency to reproduce the full backpressure chain from a Kafka broker whose disk is under
 * heavy pressure.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class KafkaStoragePressureComposer
    implements L3Composer<IncidentChaosKafkaStoragePressure> {

  public KafkaStoragePressureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosKafkaStoragePressure ann) {
    final List<Object> handles = new ArrayList<>();

    final var fs = CompositeFilesystemChaos.standard().advanced();
    handles.add(
        fs.apply(
            container,
            IoRule.latency(
                PathPrefix.path(ann.path()),
                IoOperation.WRITE,
                Duration.ofMillis(ann.latencyMs()))));
    handles.add(
        fs.apply(
            container,
            IoRule.errno(
                PathPrefix.path(ann.path()),
                IoOperation.FSYNC,
                com.macstab.chaos.filesystem.model.Errno.EIO,
                ann.probability())));

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(),
                NetOperation.SEND,
                Duration.ofMillis(ann.latencyMs() / 2),
                1.0)));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosKafkaStoragePressure ann) {
    return List.of(
        "Kafka Storage Pressure — disk fills, log segment cleanup lag, producer backpressure",
        "filesystem: WRITE latency " + ann.latencyMs() + "ms on path '" + ann.path() + "'",
        "filesystem: FSYNC → EIO, probability=" + ann.probability() + " (segment cleanup failure)",
        "connection: SEND latency " + (ann.latencyMs() / 2) + "ms (producer backpressure)",
        "severity=SEVERE — unclean segment closure, consumer rebalance under sustained disk pressure");
  }
}
