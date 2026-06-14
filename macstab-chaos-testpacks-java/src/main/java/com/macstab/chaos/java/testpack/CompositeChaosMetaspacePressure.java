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
 * <p>Fills JVM Metaspace by generating and loading synthetic classes with many fields, simulating
 * the metaspace footprint of a heavily reflective, proxy-heavy, or CGLib-instrumented application.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code METASPACE} stressor via the JVM chaos agent, which generates synthetic class
 * bytecode and loads it through isolated class loaders. The target metaspace footprint is
 * approximately {@link #targetMb()} MB. In production, metaspace exhaustion is caused by dynamic
 * proxy generation (Spring AOP, cglib), Groovy script compilation, JSP compilation, or any
 * framework that generates classes at runtime without bound.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * When Metaspace is exhausted, the JVM throws {@code OutOfMemoryError: Metaspace}. Without {@code
 * -XX:MaxMetaspaceSize}, metaspace grows until the OS refuses the allocation; with a configured
 * limit, exhaustion is predictable and reproducible.
 *
 * <h2>Industry references</h2>
 *
 * <p>JEP 122 (Remove the Permanent Generation) replaced PermGen with Metaspace in Java 8. Metaspace
 * OOM scenarios are documented in the Oracle JVM Troubleshooting Guide §3.3.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosMetaspacePressure(targetMb = 32)
 * class MetaspacePressureTest {
 *   @Test
 *   void applicationHandlesMetaspaceOom(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosMetaspacePressure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.MetaspacePressureComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosMetaspacePressure {

  /**
   * Approximate Metaspace footprint target in megabytes.
   *
   * @return target in MB; default 32
   */
  int targetMb() default 32;

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
    CompositeChaosMetaspacePressure[] value();
  }
}
