/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that marks an annotation as an L2 chaos scenario and binds it to an {@link
 * L2Composer} implementation responsible for translating the scenario into one or more chaos rules
 * and applying them to a running container.
 *
 * <p><strong>L2 vs L1.</strong> L1 annotations ({@link ChaosL1}) map a single primitive (one
 * syscall, one errno, one configurable knob) to one rule. L2 scenario annotations encode an
 * industry-canon failure mode — a named, pre-tuned composition of L1 primitives. A developer
 * writing {@code @CompositeChaosThunderingHerd} gets a documented, ready-to-use scenario without
 * knowing which {@code NetRule} to build.
 *
 * <p><strong>Same reflective-bridge pattern as L1.</strong> The composer class is named by
 * fully-qualified string and resolved via {@code Class.forName} at runtime — chaos-core has no
 * compile-time dependency on the per-module testpack libraries.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Retention(RUNTIME)
 * @Target({TYPE, METHOD})
 * @ChaosL2(
 *     composer = "com.macstab.chaos.dns.testpack.composers.NxDomainComposer",
 *     severity = Severity.SEVERE)
 * public @interface CompositeChaosNxDomain {
 *   String host() default "*";
 *   String id() default "";
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see L2Composer
 * @see Severity
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ChaosL2 {

  /**
   * Fully-qualified class name of the {@link L2Composer} implementation that handles annotations of
   * the type this meta-annotation is attached to.
   *
   * <p>Resolved via {@code Class.forName(composer())} when the framework first encounters the
   * annotation on a test class or method. The class must have a public no-arg constructor and
   * implement {@link L2Composer}.
   *
   * @return fully-qualified class name; resolution failure produces a clear {@code
   *     ExtensionConfigurationException} at test startup with a build-snippet hint
   */
  String composer();

  /**
   * Severity of this scenario — used in failure reports and CI threshold checks.
   *
   * @return scenario severity; defaults to {@link Severity#MODERATE}
   */
  Severity severity() default Severity.MODERATE;
}
