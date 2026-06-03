/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates loss of the ZooKeeper metadata service: DNS forward lookups for the ZooKeeper
 * hostname return EAI_FAIL (non-recoverable), and connection attempts to ZooKeeper are refused —
 * broker metadata becomes unavailable and producers block indefinitely.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>DNS: EAI_FAIL on all forward lookups — ZooKeeper hostname cannot be resolved; metadata
 *       bootstrap from brokers fails immediately
 *   <li>Connection: CONNECT → ECONNREFUSED at {@code toxicity} — even if a cached address is
 *       used, the connection is refused; producers block waiting for metadata
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>Producers block indefinitely; no metadata is
 * available; consumers cannot commit offsets; the entire Kafka pipeline stalls.
 *
 * <h2>Industry references</h2>
 * <p>ZooKeeper loss causing Kafka metadata unavailability is a well-known cluster failure mode:
 * without ZooKeeper quorum, brokers cannot elect a controller, partition leadership is frozen,
 * and producers block until delivery.timeout.ms is exceeded.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.DNS, LibchaosLib.NET})
 * @IncidentChaosKafkaZookeeperLoss(toxicity = 0.9)
 * class KafkaZookeeperLossTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosKafkaZookeeperLoss.List.class)
@ChaosL3(composer = "com.macstab.chaos.kafka.testpack.l3.composers.KafkaZookeeperLossComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosKafkaZookeeperLoss {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.9;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosKafkaZookeeperLoss[] value();
    }
}
