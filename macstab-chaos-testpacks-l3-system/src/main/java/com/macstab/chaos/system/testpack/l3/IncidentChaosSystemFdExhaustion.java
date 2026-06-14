/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates file descriptor limit exhaustion: existing connections remain healthy, new
 * connections and file opens fail. Health checks pass because they reuse existing sockets. The pod
 * is never restarted.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Filesystem: {@code OPEN EMFILE} at {@code toxicity} — per-process fd limit hit on all new
 *       file opens
 *   <li>Connection: {@code CONNECT ECONNREFUSED} at {@code toxicity} — new socket creation fails
 *       because sockets also consume fd slots
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Health probes succeed (they reuse an existing socket connection); the pod is never restarted by
 * the orchestrator. Only new operations fail. Root cause is only visible via {@code
 * /proc/&lt;pid&gt;/fd} or {@code lsof -p}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosSystemFdExhaustion(toxicity = 0.8)
 * class FdExhaustionTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSystemFdExhaustion.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.system.testpack.l3.composers.SystemFdExhaustionComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosSystemFdExhaustion {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of new file opens and socket connections that fail due to fd exhaustion (0.0–1.0). */
  double toxicity() default 0.8;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosSystemFdExhaustion[] value();
  }
}
