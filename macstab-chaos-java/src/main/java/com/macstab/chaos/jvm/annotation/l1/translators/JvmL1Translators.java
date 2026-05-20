/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.EnumSet;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmMethodBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Shared helpers for the JVM L1 effect translators. Reads the per-annotation interceptor binding,
 * builds the typed selector, mints a unique scenario id, and pushes the resulting scenario into
 * the {@link JvmPlanAccumulator}.
 *
 * <p>Each effect family ({@code DelayTranslator}, {@code RejectTranslator}, …) is a tiny class
 * that calls {@link #buildScenarioAndPush} with its effect-specific {@link ChaosEffect} built
 * from the annotation's attributes. This split exists because the effect attributes vary per
 * family (Delay has {@code delayMs}, Reject has {@code message}, ClockSkew has {@code skewMs} +
 * {@code mode}, etc.) and a single polymorphic translator would be cluttered with reflection
 * for every variant.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class JvmL1Translators {

  private JvmL1Translators() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Build a {@link ChaosScenario} from the annotation's {@link JvmInterceptorBinding} + the
   * caller-provided effect, register it with the accumulator, and return the minted scenario id
   * as the opaque L1 handle.
   *
   * @param container target container the JVM agent is attached to
   * @param annotation the L1 annotation declaring the interceptor binding
   * @param effect the typed effect built by the caller from the annotation's attributes
   * @return scenario id (used by remove())
   */
  static Object buildScenarioAndPush(
      final GenericContainer<?> container, final Annotation annotation, final ChaosEffect effect) {
    return JvmPlanAccumulator.instance()
        .addScenario(container, buildInterceptorScenario(annotation, effect));
  }

  /**
   * Build (but do not push) the {@link ChaosScenario} for an interceptor annotation. Exposed
   * package-private so unit tests can verify the constructed scenario without needing a running
   * container.
   */
  static ChaosScenario buildInterceptorScenario(
      final Annotation annotation, final ChaosEffect effect) {
    final JvmInterceptorBinding binding = readInterceptorBinding(annotation);
    final ChaosSelector selector =
        binding.selectorKind().build(EnumSet.of(binding.operationType()));
    return buildScenarioWith(annotation, selector, effect);
  }

  static ChaosScenario buildScenarioWith(
      final Annotation annotation, final ChaosSelector selector, final ChaosEffect effect) {
    final String id =
        JvmPlanAccumulator.instance().mintScenarioId(annotation.annotationType().getSimpleName());
    return ChaosScenario.builder(id)
        .description("L1: " + annotation.annotationType().getSimpleName())
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  /**
   * Build (but do not push) the MethodSelector-targeted scenario for an L1 carrying
   * {@link JvmMethodBinding}. Reads {@code classPattern} + {@code methodNamePattern} from the
   * annotation; at least one must be non-blank (MethodSelector rejects the all-{@code ANY}
   * combination by design to prevent JVM-wide instrumentation).
   */
  static ChaosScenario buildMethodScenario(
      final Annotation annotation, final ChaosEffect effect) {
    final JvmMethodBinding binding = readMethodBinding(annotation);
    final String classPattern = readString(annotation, "classPattern", "");
    final String methodNamePattern = readString(annotation, "methodNamePattern", "");
    if (classPattern.isBlank() && methodNamePattern.isBlank()) {
      throw new IllegalArgumentException(
          "@"
              + annotation.annotationType().getSimpleName()
              + " requires classPattern or methodNamePattern to be non-blank — MethodSelector "
              + "rejects the all-ANY combination to prevent accidental JVM-wide instrumentation.");
    }
    final NamePattern cls = classPattern.isBlank() ? NamePattern.any() : NamePattern.prefix(classPattern);
    final NamePattern mth = methodNamePattern.isBlank() ? NamePattern.any() : NamePattern.prefix(methodNamePattern);
    final ChaosSelector selector =
        ChaosSelector.method(EnumSet.of(binding.operationType()), cls, mth);
    return buildScenarioWith(annotation, selector, effect);
  }

  /** Push the method-targeted scenario through the accumulator (interceptor parallel). */
  static Object buildMethodScenarioAndPush(
      final GenericContainer<?> container, final Annotation annotation, final ChaosEffect effect) {
    return JvmPlanAccumulator.instance()
        .addScenario(container, buildMethodScenario(annotation, effect));
  }

  /**
   * Build a {@link ChaosScenario} for a stressor (which uses the unique
   * {@link com.macstab.chaos.jvm.api.ChaosSelector.StressSelector} keyed by target rather than an
   * interceptor binding).
   *
   * @param container target container
   * @param annotation the L1 stressor annotation
   * @param selector the stress selector built by the caller
   * @param effect the stressor effect built by the caller from the annotation's attributes
   * @return scenario id
   */
  static Object buildStressorScenarioAndPush(
      final GenericContainer<?> container,
      final Annotation annotation,
      final ChaosSelector selector,
      final ChaosEffect effect) {
    return JvmPlanAccumulator.instance()
        .addScenario(container, buildStressorScenario(annotation, selector, effect));
  }

  /**
   * Build (but do not push) the {@link ChaosScenario} for a stressor annotation. Exposed
   * package-private for the same testability reason as {@link #buildInterceptorScenario}.
   */
  static ChaosScenario buildStressorScenario(
      final Annotation annotation, final ChaosSelector selector, final ChaosEffect effect) {
    final String id =
        JvmPlanAccumulator.instance().mintScenarioId(annotation.annotationType().getSimpleName());
    return ChaosScenario.builder(id)
        .description("L1 stressor: " + annotation.annotationType().getSimpleName())
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  /** Remove a scenario from the accumulator. Best-effort — swallows nothing, caller handles. */
  static void removeScenario(final GenericContainer<?> container, final Object handle) {
    if (handle instanceof String id) {
      JvmPlanAccumulator.instance().removeScenario(container, id);
    }
  }

  // ==================== attribute readers ====================

  static long readLong(final Annotation annotation, final String name, final long fallback) {
    try {
      final Method m = annotation.annotationType().getMethod(name);
      final Object v = m.invoke(annotation);
      return v instanceof Long l ? l : v instanceof Integer i ? i.longValue() : fallback;
    } catch (final NoSuchMethodException e) {
      return fallback;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "failed to read " + name + "() from " + annotation.annotationType().getName(), e);
    }
  }

  static int readInt(final Annotation annotation, final String name, final int fallback) {
    return (int) readLong(annotation, name, fallback);
  }

  static String readString(final Annotation annotation, final String name, final String fallback) {
    try {
      final Method m = annotation.annotationType().getMethod(name);
      final Object v = m.invoke(annotation);
      return v instanceof String s ? s : fallback;
    } catch (final NoSuchMethodException e) {
      return fallback;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "failed to read " + name + "() from " + annotation.annotationType().getName(), e);
    }
  }

  static boolean readBoolean(final Annotation annotation, final String name, final boolean fallback) {
    try {
      final Method m = annotation.annotationType().getMethod(name);
      final Object v = m.invoke(annotation);
      return v instanceof Boolean b ? b : fallback;
    } catch (final NoSuchMethodException e) {
      return fallback;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "failed to read " + name + "() from " + annotation.annotationType().getName(), e);
    }
  }

  @SuppressWarnings("unchecked")
  static <E extends Enum<E>> E readEnum(
      final Annotation annotation, final String name, final E fallback) {
    try {
      final Method m = annotation.annotationType().getMethod(name);
      final Object v = m.invoke(annotation);
      if (v != null && v.getClass().isEnum()) {
        return (E) v;
      }
      return fallback;
    } catch (final NoSuchMethodException e) {
      return fallback;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "failed to read " + name + "() from " + annotation.annotationType().getName(), e);
    }
  }

  static JvmInterceptorBinding readInterceptorBinding(final Annotation annotation) {
    final JvmInterceptorBinding binding =
        annotation.annotationType().getAnnotation(JvmInterceptorBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@JvmInterceptorBinding meta-annotation missing on "
              + annotation.annotationType().getName());
    }
    return binding;
  }

  static JvmMethodBinding readMethodBinding(final Annotation annotation) {
    final JvmMethodBinding binding =
        annotation.annotationType().getAnnotation(JvmMethodBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@JvmMethodBinding meta-annotation missing on "
              + annotation.annotationType().getName());
    }
    return binding;
  }

  /** True iff the annotation carries {@link JvmMethodBinding} rather than {@link JvmInterceptorBinding}. */
  static boolean isMethodBinding(final Annotation annotation) {
    return annotation.annotationType().isAnnotationPresent(JvmMethodBinding.class);
  }

  /** Bridge: helper to enforce OperationType is unused for stressors (compile-time check). */
  static void requireNonNullOp(final OperationType op) {
    if (op == null) {
      throw new IllegalArgumentException("operation must not be null");
    }
  }
}
