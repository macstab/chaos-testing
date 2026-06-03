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
 * <p>Injects a {@code ClassNotFoundException} or {@code NoClassDefFoundError} into class-loading
 * operations matching {@link #classPattern()}, simulating a corrupted class path, a missing JAR,
 * or a class that was removed between build and deploy.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code CLASS_LOAD} operations via the JVM chaos agent and injects an exception for
 * class names matching the configured pattern. In production, class-load failures occur after
 * hot-deploy of an incomplete artifact, after an OSGi bundle is uninstalled while still in use, or
 * when a reflective {@code Class.forName()} relies on a class that is conditionally present.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * A failed class load for a core class (e.g. a Spring bean, a JDBC driver) will prevent the
 * application context from starting. A failed load for an optional extension class must be handled
 * gracefully; code that does not catch {@code ClassNotFoundException} from reflective loads will
 * propagate the error to callers unexpectedly.
 *
 * <h2>Industry references</h2>
 *
 * <p>The Java class-loading mechanism and its failure modes are documented in JVM spec §5.3
 * "Creation and Loading". OSGi classloader isolation problems are a well-known source of
 * {@code NoClassDefFoundError} in enterprise Java deployments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosClassLoadFailure(classPattern = "*")
 * class ClassLoadFailureTest {
 *   @Test
 *   void applicationHandlesMissingOptionalClass(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosClassLoadFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ClassLoadFailureComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosClassLoadFailure {

  /**
   * Class name pattern to target (prefix or {@code "*"} for all classes).
   *
   * @return class pattern; default "*"
   */
  String classPattern() default "*";

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
    CompositeChaosClassLoadFailure[] value();
  }
}
