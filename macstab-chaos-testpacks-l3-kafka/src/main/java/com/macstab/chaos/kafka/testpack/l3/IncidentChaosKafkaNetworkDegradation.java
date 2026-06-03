/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates sustained network degradation between producers/consumers and the Kafka cluster:
 * bidirectional latency causes produce requests to time out, fetch requests to stall, and consumer
 * lag to build until a rebalance is triggered.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: SEND latency of {@code latencyMs} ms — produce requests and metadata fetches
 *       are delayed; {@code request.timeout.ms} violations accumulate in high-throughput paths
 *   <li>Connection: RECV latency of {@code latencyMs} ms — fetch responses arrive late; consumer
 *       lag grows as the fetch loop falls behind; second handle covers bidirectional degradation
 *   <li>JVM: NetworkException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       reproduces the client-level error logged when sustained latency exceeds the configured
 *       request timeout, exercising NetworkException handling in the application
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Moderate</strong><br>Sustained throughput drop causes consumer lag to
 * accumulate; once lag exceeds the rebalance threshold or {@code max.poll.interval.ms} is breached,
 * the consumer group rebalances, amplifying the initial degradation.
 *
 * <h2>Industry references</h2>
 * <p>Sustained network throughput drop with consumer lag accumulation eventually triggering
 * rebalance is a common pattern in cloud-hosted Kafka deployments during network congestion
 * events. Documented in Confluent operator guides as a trigger for cascading lag buildup.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosKafkaNetworkDegradation(latencyMs = 500L)
 * class KafkaNetworkDegradationTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosKafkaNetworkDegradation.List.class)
@ChaosL3(composer = "com.macstab.chaos.kafka.testpack.l3.composers.KafkaNetworkDegradationComposer", severity = Severity.MODERATE)
public @interface IncidentChaosKafkaNetworkDegradation {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Milliseconds of latency injected into each SEND and RECV syscall. */
    long latencyMs() default 200L;

    /** Class name prefix used to match Kafka client methods for exception injection. */
    String classPattern() default "org.apache.kafka";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosKafkaNetworkDegradation[] value();
    }
}
