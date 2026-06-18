/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

/**
 * Severity classification for L2 chaos scenarios.
 *
 * <p>Severity describes the production impact class of the failure mode — how bad it gets if the
 * service fails this scenario in real life. This classification drives failure report colour coding
 * and CI threshold checks.
 *
 * <ul>
 *   <li>{@link #MILD} — service degraded but responsive; no operator intervention needed
 *   <li>{@link #MODERATE} — service drops some requests but recovers on its own
 *   <li>{@link #SEVERE} — service impaired enough to require manual intervention
 *   <li>{@link #CRITICAL} — service outage; data loss or extended downtime possible
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosL2
 */
public enum Severity {

  /**
   * Service degraded but responsive. Elevated error rate, increased latency — no operator
   * intervention required. Examples: slow DNS resolution, occasional EINTR on retried syscalls.
   */
  MILD,

  /**
   * Service drops some requests but recovers on its own within seconds. Retries succeed; no data
   * loss; no manual intervention required. Examples: transient DNS failure, periodic GC pause,
   * brief connection timeout.
   */
  MODERATE,

  /**
   * Service significantly impaired. Elevated failure rate sustained beyond retry budgets; may
   * require operator action (restart, failover) to restore. Examples: disk full, thread pool
   * exhausted, connection refused at 100%, clock skew breaking distributed locks.
   */
  SEVERE,

  /**
   * Service outage. Data loss possible; extended downtime; immediate operator intervention
   * mandatory. Examples: OOM-kill, data corruption, cascading deadlock, thundering herd on restart.
   */
  CRITICAL
}
