/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.http.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates TLS negotiation failures under load: connection resets during the TCP handshake
 * phase abort SSL/TLS sessions before cipher negotiation completes, reproducing the failure mode
 * seen when certificates expire, cipher suites mismatch, or a TLS terminator is overwhelmed.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: CONNECT → ECONNRESET at toxicity {@code toxicity} — TCP connection resets abort
 *       the TLS handshake at the socket level, mimicking mid-handshake RST packets
 *   <li>JVM: SSLHandshakeException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       reproduces the JSSE exception thrown when TLS negotiation fails, testing that the
 *       application handles SSL errors distinctly from plain connection errors
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * SSL failures under load cause connection-pool exhaustion as handshake threads block; combined
 * with the higher CPU cost of failed TLS negotiations, the load balancer and TLS terminator can be
 * driven into saturation.
 *
 * <h2>Industry references</h2>
 *
 * <p>TLS handshake failure storms are documented in Cloudflare and Let's Encrypt post-mortems
 * around certificate renewal failures and in Java application incidents triggered by JDK
 * cipher-suite policy changes between minor versions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosHttpSslHandshakeStorm(toxicity = 0.7)
 * class SslHandshakeStormTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosHttpSslHandshakeStorm.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.http.testpack.l3.composers.HttpSslHandshakeStormComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosHttpSslHandshakeStorm {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT syscalls that return ECONNRESET (0.0–1.0). */
  double toxicity() default 0.7;

  /** Class name prefix used to match SSL/TLS methods for SSLHandshakeException injection. */
  String classPattern() default "javax.net.ssl";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosHttpSslHandshakeStorm[] value();
  }
}
