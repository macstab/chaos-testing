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
 * <p>Simulates clock skew between a Kafka broker and its clients: the realtime clock is shifted
 * forward, RECV latency is injected to add jitter, and the application receives TimestampExceptions
 * when timestamp-based partition routing or log compaction decisions diverge between nodes.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Time: REALTIME clock skew of {@code skewMs} ms at probability {@code probability} — broker
 *       and client wall-clocks diverge; timestamp-indexed segments become inconsistent and
 *       compaction decisions based on wall time may evict live data
 *   <li>Connection: RECV latency of 20 ms on every receive syscall — adds network jitter that
 *       compounds with the clock skew, widening the observed timestamp window
 *   <li>JVM: TimestampException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       reproduces the client-level error raised when timestamp routing fails under skew
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Messages land in wrong partition segments under timestamp-based routing; offset commit drift is
 * possible when wall-clock mismatches cause log compaction to remove messages that consumers have
 * not yet processed.
 *
 * <h2>Industry references</h2>
 *
 * <p>Kafka timestamp routing under clock skew — messages landing in wrong partition segments and
 * offset commit drift — is documented in KIP-32 and operator post-mortems involving NTP
 * desynchronisation on broker or client hosts.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.TIME, LibchaosLib.NET})
 * @IncidentChaosKafkaClockDrift(skewMs = 2000L, probability = 1.0)
 * class KafkaClockDriftTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosKafkaClockDrift.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.kafka.testpack.l3.composers.KafkaClockDriftComposer",
    severity = Severity.MODERATE)
public @interface IncidentChaosKafkaClockDrift {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Milliseconds by which the realtime clock is shifted forward. */
  long skewMs() default 1000L;

  /** Probability (0.0–1.0) that the clock offset is applied. */
  double probability() default 1.0;

  /** Class name prefix used to match Kafka client methods for exception injection. */
  String classPattern() default "org.apache.kafka";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosKafkaClockDrift[] value();
  }
}
