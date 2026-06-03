/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

/**
 * Adapter that translates a single L2 scenario annotation into one or more chaos rules and applies
 * them to a running container. One implementation per L2 scenario annotation.
 *
 * <p><strong>L2 vs L1.</strong> An {@link L1Translator} applies one rule and returns one opaque
 * handle. An {@code L2Composer} applies a named scenario — typically two to five primitive rules
 * that together simulate a well-known production failure mode — and returns a list of handles, one
 * per rule applied.
 *
 * <p><strong>Lifecycle contract.</strong> {@link #apply} is called from {@code
 * ChaosTestingExtension}'s {@code BeforeAll} / {@code BeforeEach} phase once per (annotation ×
 * matching container) pair. The returned handle list is stored by the framework and passed back to
 * {@link #removeAll} from {@code AfterAll} (class-scope) or {@code AfterEach} (method-scope).
 *
 * <p><strong>Error contract.</strong>
 *
 * <ul>
 *   <li>Developer errors (invalid attribute, missing container, mis-typed id) bubble as {@code
 *       IllegalArgumentException} — the framework wraps them in {@code
 *       ExtensionConfigurationException} with annotation source context.
 *   <li>Environment unavailability (libchaos {@code .so} absent, backend doesn't support the verb)
 *       bubbles as a runtime exception from the underlying {@code Composite<X>Chaos}; the framework
 *       wraps it in {@code ExtensionConfigurationException} (L2 always errors on missing env).
 *   <li>{@link #removeAll} must <strong>never throw</strong>. Swallow + log on failure.
 * </ul>
 *
 * <p><strong>Thread safety.</strong> Implementations are instantiated once per JVM and reused
 * across all test classes — keep them stateless.
 *
 * <p><strong>describe contract.</strong> {@link #describe} is called at apply time to populate the
 * failure report. It must be cheap (no I/O, no container interaction) and return a non-empty list
 * of human-readable strings that explain the chaos being applied.
 *
 * @param <A> the L2 scenario annotation type this composer handles
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosL2
 */
public interface L2Composer<A extends Annotation> {

  /**
   * Build the chaos rules encoded by {@code annotation} and apply them to {@code container}. Return
   * an opaque handle list that {@link #removeAll} can use later — one handle per rule applied.
   *
   * @param container the running container to apply the rules to (never {@code null})
   * @param annotation the L2 scenario annotation instance to translate (never {@code null})
   * @return opaque handles; must not be null or empty (returning an empty list means nothing was
   *     applied, which is a configuration error)
   * @throws IllegalArgumentException for invalid attribute values (developer error)
   * @throws RuntimeException for environment-unavailability — the framework wraps and reports
   */
  List<Object> apply(GenericContainer<?> container, A annotation);

  /**
   * Best-effort removal of all rules previously applied by {@link #apply}. Must not throw — log
   * failures individually and continue removing the remaining handles.
   *
   * @param container the container the handles were applied to (never {@code null})
   * @param handles the list previously returned by {@link #apply} (never {@code null})
   */
  void removeAll(GenericContainer<?> container, List<Object> handles);

  /**
   * Return a human-readable description of the chaos this scenario applies, given the annotation's
   * attribute values. Used in failure reports.
   *
   * <p>Must be cheap (no I/O). Return a non-empty list. Each string should be one line.
   *
   * @param annotation the annotation instance to describe (never {@code null})
   * @return non-empty list of description lines
   */
  List<String> describe(A annotation);
}
