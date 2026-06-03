/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates the compound failure when a GC pause causes a consumer to exceed
 * {@code max.poll.interval.ms}: the broker sees the consumer as dead, triggers a group rebalance,
 * and the consumer rejoins — potentially re-processing messages already committed by other members.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>JVM: RuntimeException injected on class prefix {@code classPattern} — simulates the
 *       GC pause that blocks the poll loop from returning within the deadline
 *   <li>Connection: RECV latency of {@code gcPauseMs} ms on every receive syscall — simulates
 *       the broker-side view of a consumer that stops sending heartbeats during the pause window
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Severe</strong><br>Consumer group rebalance causes all partitions to be
 * briefly unassigned; at-least-once processing semantics mean duplicate records are delivered to
 * consumers that take over partitions mid-stream; downstream deduplication logic is exercised.
 *
 * <h2>Industry references</h2>
 * <p>GC pause exceeds {@code max.poll.interval.ms} → consumer group rebalance → duplicate
 * processing is documented in the Confluent Kafka consumer tuning guide and in post-mortems from
 * JVM-based consumers running mixed workloads with large heap allocations.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosKafkaConsumerRebalance(gcPauseMs = 8000L)
 * class KafkaConsumerRebalanceTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosKafkaConsumerRebalance.List.class)
@ChaosL3(composer = "com.macstab.chaos.kafka.testpack.l3.composers.KafkaConsumerRebalanceComposer", severity = Severity.SEVERE)
public @interface IncidentChaosKafkaConsumerRebalance {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Milliseconds of simulated GC pause injected into RECV and via JVM exception. */
    long gcPauseMs() default 5000L;

    /** Class name prefix used to match Kafka consumer methods for exception injection. */
    String classPattern() default "org.apache.kafka";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosKafkaConsumerRebalance[] value();
    }
}
