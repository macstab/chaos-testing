/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a JVM safepoint cascade: a GC safepoint pause triggers simultaneous timeout storms
 * in every connected system. During the STW pause, in-flight sockets time out, DNS re-resolutions
 * fail, and connection pool health checks expire — all at the same moment the JVM resumes.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: SafepointStorm every {@code gcIntervalMs} ms — forces stop-the-world pauses that make
 *       all downstream timeouts fire simultaneously on resume
 *   <li>Connection: RECV → ECONNRESET at {@code toxicity} — HikariCP, Kafka producers, and
 *       ZooKeeper clients lose their sockets during the pause window
 *   <li>DNS: EAI_AGAIN on every forward lookup — ZooKeeper session renewal and service discovery
 *       re-resolution fail transiently
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * A single GC pause causes simultaneous failure across all dependent systems; retry storms from
 * multiple clients amplify the disruption.
 *
 * <h2>Industry references</h2>
 *
 * <p>GC-pause-induced timeout cascades: documented in multiple Kafka and ZooKeeper production
 * incidents where a single long GC pause caused Sentinel/leader election storms, connection pool
 * exhaustion, and cascading retry amplification across the dependent service mesh.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
 * @IncidentChaosJvmSafepointCascade(gcIntervalMs = 200L, toxicity = 0.8)
 * class SafepointCascadeTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmSafepointCascade.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmSafepointCascadeComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosJvmSafepointCascade {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Interval between forced safepoints in milliseconds. */
  long gcIntervalMs() default 100L;

  /** Fraction of RECV syscalls that return ECONNRESET (0.0–1.0). */
  double toxicity() default 0.7;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmSafepointCascade[] value();
  }
}
