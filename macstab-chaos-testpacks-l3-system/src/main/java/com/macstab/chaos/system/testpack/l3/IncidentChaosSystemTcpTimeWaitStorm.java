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
 * <p>Simulates a TCP TIME_WAIT storm caused by high connection churn: the ephemeral port range is
 * exhausted by sockets stuck in TIME_WAIT state, causing new outbound connections to fail with
 * {@code EADDRNOTAVAIL}. From the application's perspective this manifests as {@code ECONNREFUSED}
 * (OkHttp issue #4354).
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: {@code CONNECT ECONNREFUSED} at {@code toxicity} — ephemeral port exhaustion
 *       manifests as connection failure on new outbound attempts
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * New outbound connections fail with {@code EADDRNOTAVAIL}; existing connections are completely
 * unaffected. Pod restarts do not help because TIME_WAIT sockets are kernel-owned. Diagnosis
 * requires {@code ss -s} or {@code netstat -an | grep TIME_WAIT | wc -l}.
 *
 * <h2>Industry references</h2>
 *
 * <p>OkHttp #4354: high-throughput services with short-lived HTTP connections exhaust the ephemeral
 * port range when {@code SO_REUSEADDR} / {@code tcp_tw_reuse} are not configured.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosSystemTcpTimeWaitStorm(toxicity = 0.3)
 * class TcpTimeWaitStormTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSystemTcpTimeWaitStorm.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.system.testpack.l3.composers.SystemTcpTimeWaitStormComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosSystemTcpTimeWaitStorm {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of outbound {@code connect()} calls that fail with ECONNREFUSED (0.0–1.0). */
  double toxicity() default 0.3;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosSystemTcpTimeWaitStorm[] value();
  }
}
