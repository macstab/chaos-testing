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
 * <p>Simulates an unclean leader election caused by ISR replication lag: high RECV latency causes
 * the in-sync replica set to shrink as brokers fall behind, resulting in a lagged broker being
 * promoted to leader when the current leader fails. Combined with JVM TimeoutException injection
 * this tests the application's tolerance for the resulting message loss and consumer timestamp
 * regression.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV latency of {@code latencyMs} ms — simulates ISR replication lag causing
 *       brokers to fall out of the in-sync replica set
 *   <li>JVM: TimeoutException on class prefix {@code classPattern} at METHOD_EXIT — models
 *       producer/consumer timeout as the lagged broker is promoted and cannot serve requests
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * 500+ messages permanently lost; consumer timestamp regression is possible as the lagged broker's
 * log head is behind the old leader's committed offset; dense-ID assumptions may be violated.
 *
 * <h2>Industry references</h2>
 *
 * <p>Unclean leader election and ISR shrinkage under replication lag is documented in the Kafka
 * documentation §"Replication" and §"Leader election". When {@code
 * unclean.leader.election.enable=true}, the elected leader may be missing messages that were
 * acknowledged by the old leader, causing permanent data loss.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosKafkaUncleanLeaderElection(latencyMs = 500L, classPattern = "org.apache.kafka")
 * class KafkaUncleanLeaderElectionTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosKafkaUncleanLeaderElection.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.kafka.testpack.l3.composers.KafkaUncleanLeaderElectionComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosKafkaUncleanLeaderElection {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** RECV latency in milliseconds simulating ISR replication lag. */
  long latencyMs() default 500L;

  /** Class name prefix used to match Kafka producer/consumer methods for exception injection. */
  String classPattern() default "org.apache.kafka";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosKafkaUncleanLeaderElection[] value();
  }
}
