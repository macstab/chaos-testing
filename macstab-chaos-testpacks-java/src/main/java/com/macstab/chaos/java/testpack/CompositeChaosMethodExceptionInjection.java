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
 * <p>Throws a configurable exception at the entry point of every method in the target container's
 * JVM whose class name starts with {@link #classPattern()} and whose method name starts with
 * {@link #methodNamePattern()}, providing a general-purpose fault-injection escape hatch for
 * application-level methods.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code METHOD_ENTER} operations via the JVM chaos agent using prefix-match
 * patterns for class and method names. The exception class is instantiated with the configured
 * message. In production, arbitrary method failures occur when a transient dependency (network,
 * disk, external API) becomes unavailable — this scenario simulates exactly that entry-point
 * failure without requiring the dependency to be broken.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Scope is determined by how broad the patterns are. Narrow patterns (specific class + method)
 * produce targeted failures; broad patterns can cause widespread application failure. Always
 * constrain both patterns to the specific code under test.
 *
 * <h2>Industry references</h2>
 *
 * <p>Netflix's "FIT: Failure Injection Testing" (engineering blog, 2014) describes exactly this
 * pattern — injecting arbitrary method failures into production traffic at a low percentage to
 * validate resiliency without coordinating full outages.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosMethodExceptionInjection(
 *     classPattern = "com.example.service",
 *     methodNamePattern = "charge",
 *     exceptionClass = "com.example.PaymentException")
 * class MethodExceptionTest {
 *   @Test
 *   void orderRollsBackOnPaymentFailure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosMethodExceptionInjection.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.MethodExceptionInjectionComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosMethodExceptionInjection {

  /**
   * Prefix matched against the binary class name (e.g. {@code "com.example.service"}).
   * Defaults to {@code "*"} — matches every class. Override to scope the fault to a specific
   * package or class prefix.
   */
  String classPattern() default "*";

  /**
   * Prefix matched against the method name (e.g. {@code "save"}). Defaults to {@code "*"} —
   * matches every method. Override to target a specific method name or prefix.
   */
  String methodNamePattern() default "*";

  /**
   * Binary class name of the exception to throw.
   *
   * @return exception class name; default "java.io.IOException"
   */
  String exceptionClass() default "java.io.IOException";

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
    CompositeChaosMethodExceptionInjection[] value();
  }
}
