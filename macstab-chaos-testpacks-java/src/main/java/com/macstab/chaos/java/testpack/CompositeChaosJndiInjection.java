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
 * <p>Causes every {@code InitialContext.lookup()} call to throw a {@code NamingException},
 * simulating a missing or unreachable JNDI server — for example, a JMS provider, a DataSource
 * configured in a Java EE container, or a legacy EJB remote reference.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code JNDI_LOOKUP} operations via the JVM chaos agent and injects a {@code
 * NamingException} at the configured probability. In production, JNDI lookup failures occur when
 * the application server's naming service is unavailable, when a JMS broker is down, or when a
 * DataSource JNDI name is mis-configured after a deployment.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * JNDI lookups in {@code @PostConstruct} or lazy-init code fail fatally during context startup.
 * Lookups in request-handling code will throw on every request until the naming service recovers.
 * Applications that cache lookup results do not suffer repeated failures but may use stale
 * references after the JNDI tree changes.
 *
 * <h2>Industry references</h2>
 *
 * <p>The Java Naming and Directory Interface (JNDI) API is specified in JSR-168. Log4Shell
 * (CVE-2021-44228) demonstrated that JNDI lookups are a high-impact attack surface; this scenario
 * validates that the application handles JNDI failures gracefully without exposing that surface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosJndiInjection(probability = 1.0)
 * class JndiInjectionTest {
 *   @Test
 *   void applicationStartsWhenJndiUnavailable(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosJndiInjection.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.JndiInjectionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosJndiInjection {

  /**
   * Probability in {@code (0.0, 1.0]} that a JNDI lookup throws {@code NamingException}.
   *
   * @return probability; default 1.0
   */
  double probability() default 1.0;

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
    CompositeChaosJndiInjection[] value();
  }
}
