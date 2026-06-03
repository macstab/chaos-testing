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
 * <h2>What this is</h2>
 *
 * <p>Causes TLS/SSL handshakes to fail with an {@code SSLHandshakeException} at probability
 * {@link #probability()}, simulating expired certificates, cipher-suite mismatches, or a TLS
 * termination proxy that is temporarily unavailable.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code SSL_HANDSHAKE} operations via the JVM chaos agent and injects an
 * {@code SSLHandshakeException}. In production, TLS handshake failures occur after certificate
 * rotation, after a cipher-suite policy change, or when an intermediate CA is revoked without
 * updating the trust store.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Every outbound HTTPS or mutual-TLS connection fails without a valid handshake. Applications
 * that do not implement retry with exponential backoff will propagate the error immediately.
 * Internal services that rely on mTLS for authentication will become unreachable simultaneously
 * if the certificate issue is cluster-wide.
 *
 * <h2>Industry references</h2>
 *
 * <p>RFC 8446 (TLS 1.3) §6.2 defines the TLS alert protocol used to signal handshake failures.
 * Let's Encrypt's documentation on certificate auto-renewal and the impact of rotation on running
 * services covers the real-world context.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosSslHandshakeFailure(probability = 0.7)
 * class SslHandshakeFailureTest {
 *   @Test
 *   void applicationRetriesOnSslHandshakeFailure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSslHandshakeFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.SslHandshakeFailureComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosSslHandshakeFailure {

  /**
   * Probability in {@code (0.0, 1.0]} that a TLS handshake throws {@code SSLHandshakeException}.
   *
   * @return probability; default 0.7
   */
  double probability() default 0.7;

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
    CompositeChaosSslHandshakeFailure[] value();
  }
}
