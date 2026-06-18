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
 * <p>Simulates a complete Kafka broker failure: all new connections are refused, DNS resolution of
 * broker addresses transiently fails, and the application JVM receives TimeoutExceptions from the
 * Kafka producer/consumer client, triggering the exponential-backoff retry storm.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at toxicity {@code toxicity} — broker port not
 *       accepting; producer metadata requests and consumer fetches all fail immediately
 *   <li>DNS: EAI_AGAIN on every forward lookup — bootstrap server address re-resolution fails
 *       during the reconnect window, extending the outage past the initial connection failure
 *   <li>JVM: TimeoutException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       reproduces the application-level symptom of a producer retry storm under broker absence
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Producers accumulate unacked batches until {@code delivery.timeout.ms} expires; consumers lose
 * group membership as heartbeats stop; offset commit drift is possible if auto-commit fires during
 * the outage window.
 *
 * <h2>Industry references</h2>
 *
 * <p>Broker down → producer/consumer retry storm with exponential backoff collision is a
 * well-documented Kafka failure mode. Producers in high-throughput pipelines exhaust {@code
 * buffer.memory} before the broker recovers; consumers trigger rebalances as {@code
 * max.poll.interval.ms} is exceeded waiting for fetch responses.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
 * @IncidentChaosKafkaBrokerFailure(toxicity = 0.9, classPattern = "org.apache.kafka")
 * class KafkaBrokerFailureTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosKafkaBrokerFailure.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.kafka.testpack.l3.composers.KafkaBrokerFailureComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosKafkaBrokerFailure {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
  double toxicity() default 0.8;

  /** Class name prefix used to match Kafka client methods for exception injection. */
  String classPattern() default "org.apache.kafka";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosKafkaBrokerFailure[] value();
  }
}
