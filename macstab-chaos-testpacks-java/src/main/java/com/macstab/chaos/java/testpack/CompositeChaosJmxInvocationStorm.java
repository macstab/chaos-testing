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
 * <p>Causes {@code MBeanServer.invoke()} calls to throw a {@code ReflectionException} at
 * probability {@link #probability()}, simulating a JMX management server that is unresponsive or
 * returning errors — for example, when a monitoring agent loses its JMX connection and retries
 * rapidly, creating a storm of failing invocations.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code JMX_INVOKE} operations via the JVM chaos agent and injects a
 * {@code ReflectionException}. In production, JMX invocation storms arise when management tools
 * (Jolokia, JConsole, APM agents) poll or invoke JMX operations at high frequency and the
 * MBean implementation is slow or throws.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * JMX is a management plane, not a data plane. Failing JMX invocations typically cause
 * monitoring gaps rather than application errors. However, some applications use JMX to read
 * configuration or trigger operations; those code paths will fail and must be handled gracefully.
 *
 * <h2>Industry references</h2>
 *
 * <p>JMX specification (JSR-3) §4.2 documents {@code MBeanServer.invoke()} and its exception
 * contract. Spring Boot Actuator exposes application metrics over JMX; a failing JMX layer causes
 * monitoring blackout in environments that rely on JMX-pull metrics.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosJmxInvocationStorm(probability = 0.3)
 * class JmxInvocationStormTest {
 *   @Test
 *   void monitoringRemainsAvailableWhenJmxIsUnstable(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosJmxInvocationStorm.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.JmxInvocationStormComposer",
    severity = Severity.MILD)
public @interface CompositeChaosJmxInvocationStorm {

  /**
   * Probability in {@code (0.0, 1.0]} that an MBeanServer.invoke() throws.
   *
   * @return probability; default 0.3
   */
  double probability() default 0.3;

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
    CompositeChaosJmxInvocationStorm[] value();
  }
}
