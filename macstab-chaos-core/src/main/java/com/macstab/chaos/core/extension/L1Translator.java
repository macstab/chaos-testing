/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

/**
 * Adapter that translates a single L1 annotation instance into a chaos rule and applies it to a
 * running container. One implementation per (chaos-module × effect-kind); parameterised across the
 * family of annotations it owns via a static lookup table on annotation type.
 *
 * <p><strong>Lifecycle contract.</strong> {@link #apply} is called from {@code
 * ChaosTestingExtension}'s {@code BeforeAll} / {@code BeforeEach} phase (depending on whether the
 * annotation is at class or method scope) once per (annotation × matching container) pair. The
 * returned opaque handle is stored by the framework and passed back to {@link #remove} from {@code
 * AfterEach} (method-scope) or {@code AfterAll} (class-scope).
 *
 * <p><strong>Error contract.</strong>
 *
 * <ul>
 *   <li>Developer errors (invalid attribute, missing container, mis-typed id) bubble as {@code
 *       IllegalArgumentException} — the framework wraps them in {@code
 *       ExtensionConfigurationException} with annotation source context appended.
 *   <li>Environment unavailability (libchaos {@code .so} absent, capability not supported by the
 *       active backend) bubbles as {@code ChaosUnsupportedOperationException} (or {@code
 *       LibchaosNotPreparedException}) from the underlying {@code Composite<X>Chaos}; the framework
 *       catches it and honours the annotation's {@code OnMissingEnv} attribute.
 *   <li>{@link #remove} must <strong>never throw</strong>. Swallow + log on failure; the framework
 *       will fall back to {@code container.reset()} if cleanup is unreliable.
 * </ul>
 *
 * <p><strong>Thread safety.</strong> Implementations are instantiated once per JVM and reused
 * across all test classes — keep them stateless.
 *
 * @param <A> the L1 annotation type this translator handles; use the bound annotation type for a
 *     single-annotation translator, or {@link Annotation} for parameterised family translators
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosL1
 */
public interface L1Translator<A extends Annotation> {

  /**
   * Build the chaos rule encoded by {@code annotation} and apply it to {@code container}. Return an
   * opaque handle that {@link #remove} can use later.
   *
   * @param container the running container to apply the rule to (never {@code null})
   * @param annotation the L1 annotation instance to translate (never {@code null})
   * @return opaque handle used by {@link #remove}; the framework treats the type as opaque
   * @throws IllegalArgumentException for invalid attribute values (developer error)
   * @throws RuntimeException for environment-unavailability — the framework will catch and route
   *     through the annotation's {@code OnMissingEnv} policy
   */
  Object apply(GenericContainer<?> container, A annotation);

  /**
   * Best-effort removal of a rule previously applied by {@link #apply}. Must not throw — log
   * failures and let the framework fall back to {@code container.reset()} if cleanup is unreliable.
   *
   * @param container the container the handle was applied to (never {@code null})
   * @param handle the handle previously returned by {@link #apply} (never {@code null})
   */
  void remove(GenericContainer<?> container, Object handle);
}
