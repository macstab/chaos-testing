/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Skews the in-process JVM clock — intercepting {@code Instant.now()}, {@code
 * System.currentTimeMillis()}, and {@code LocalDateTime.now()} — by {@link #skewMs()} milliseconds,
 * simulating NTP drift, leap-second adjustments, or virtual machine clock resynchronisation.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts JVM-runtime clock operations via the JVM chaos agent and adds the configured skew
 * to every time reading. Unlike OS-level clock skew (which would affect all containers), this is a
 * targeted, in-process JVM interception that affects only the applications using the standard Java
 * time APIs. In production, clock skew causes JWT expiry validation failures, distributed lock TTL
 * miscalculations, and OAuth token invalidation.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Small skews (< 1 s) may cause intermittent token validation failures. Large skews (> 60 s) cause
 * systematic JWT/OAuth failures, distributed-lock expiry, and Kerberos authentication breakage
 * (5-minute tolerance). The application usually recovers when the skew is removed.
 *
 * <h2>Industry references</h2>
 *
 * <p>Google TrueTime (Spanner §3) was designed specifically because NTP clock skew causes
 * linearisability violations in distributed systems. RFC 7519 (JWT) §4.1.4 ({@code exp} claim) is
 * sensitive to even 30-second clock differences between issuer and validator.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosClockSkewInProcess(skewMs = 500)
 * class ClockSkewTest {
 *   @Test
 *   void jwtValidationToleratesSmallClockSkew(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosClockSkewInProcess.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ClockSkewInProcessComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosClockSkewInProcess {

  /**
   * Clock skew in milliseconds (positive = future, negative = past).
   *
   * @return skew in ms; default 500
   */
  long skewMs() default 500L;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosClockSkewInProcess[] value();
  }
}
