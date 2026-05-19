/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that marks an annotation as an L1 chaos primitive and binds it to a {@link
 * L1Translator} implementation responsible for converting the annotation into a rule and applying
 * it to a running container.
 *
 * <p><strong>Why a meta-annotation and not a SPI.</strong> chaos-core must not have a compile-time
 * dependency on the per-chaos-kind modules (memory, process, time, dns, connection, filesystem,
 * jvm-agent) — that would break the modular classpath contract and prevent partial-classpath
 * usage. The same reflective-bridge pattern that powers {@code @JvmAgentChaos} (see
 * {@code ChaosTestingExtension.java:441-499}) carries L1 here: the translator class is named by
 * fully-qualified string and resolved via {@code Class.forName} at runtime. The L1 annotation
 * is the loose-coupling boundary.
 *
 * <p><strong>Per-annotation contract, not per-module.</strong> Each L1 annotation carries its own
 * {@code @ChaosL1(translator = "…")}, so adding a new annotation never requires changes to a
 * service-loader file or to chaos-core.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Retention(RUNTIME)
 * @Target({TYPE, METHOD})
 * @ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
 * public @interface ChaosMmapAnonEnomem {
 *   double probability() default 1.0;
 *   String id() default "";
 *   OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see L1Translator
 * @see OnMissingEnv
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ChaosL1 {

  /**
   * Fully-qualified class name of the {@link L1Translator} implementation that handles
   * annotations of the type this meta-annotation is attached to.
   *
   * <p>Resolved via {@code Class.forName(translator())} when the framework first encounters the
   * annotation on a test class. The class must have a public no-arg constructor and implement
   * {@link L1Translator}.
   *
   * <p>Specified as a string (not {@code Class<? extends L1Translator>}) so chaos-core can stay
   * free of compile-time dependencies on the per-module translator classes.
   *
   * @return fully-qualified class name; resolution failure produces a clear
   *     {@code ExtensionConfigurationException} at test startup with a build-snippet hint
   */
  String translator();
}
