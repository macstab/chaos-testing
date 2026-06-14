/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a Hazelcast split-brain event: a network partition causes cluster members to split
 * into two independent partitions that each continue accepting writes. When the partition heals,
 * the merge strategy silently discards one side's writes, causing data loss with no exception
 * visible to the application.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV ECONNRESET at {@code toxicity} — disrupts member-to-member heartbeats,
 *       causing partition detection and split into two independent clusters
 *   <li>DNS: EAI_AGAIN on any forward lookup — disrupts member discovery during the partition,
 *       preventing automatic re-join
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Silent data loss on partition healing. No exception is visible to the application; writes
 * accepted during the split are silently dropped by the loser side of the merge.
 *
 * <h2>Industry references</h2>
 *
 * <p>Hazelcast split-brain merge policies are documented in the official Hazelcast reference
 * manual. Production incidents involving split-brain data loss have been reported across multiple
 * cloud environments where network ACL changes caused asymmetric partition events.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosHazelcastSplitBrain(toxicity = 0.6)
 * class HazelcastSplitBrainTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosHazelcastSplitBrain.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.cache.testpack.l3.composers.HazelcastSplitBrainComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosHazelcastSplitBrain {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of RECV syscalls that return ECONNRESET (0.0–1.0). */
  double toxicity() default 0.5;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosHazelcastSplitBrain[] value();
  }
}
