/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.CompositeJavaChaos;
import com.macstab.chaos.jvm.annotation.l1.ChaosJvmPlan;

/**
 * L1 translator for {@link ChaosJvmPlan}. Loads the named classpath resource and pushes its
 * contents to the agent via {@link CompositeJavaChaos#applyPlan}. On cleanup, calls
 * {@link CompositeJavaChaos#clearPlan} — the agent's wire model is wholesale plan replacement,
 * not per-rule add/remove, so a single handle per container covers the whole plan.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmPlanTranslator implements L1Translator<Annotation> {

  /** Marker handle — translator is plan-scoped, not rule-scoped, so identity is sufficient. */
  private static final Object PLAN_HANDLE = new Object();

  /** Public no-arg constructor required by the L1 translator contract. */
  public JvmPlanTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final String resource = readPlanJsonResource(annotation);
    final String planJson = loadResource(resource, annotation);

    final CompositeJavaChaos chaos = new CompositeJavaChaos();
    if (!chaos.isActive(container)) {
      throw new ChaosUnsupportedOperationException(
          "JVM agent is not active on the container. Did the test class declare "
              + "@JvmAgentChaos? The agent jar must be wired pre-start via JavaAgentTransport.");
    }
    chaos.applyPlan(container, planJson);
    return PLAN_HANDLE;
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    // Wholesale reset — the agent has no per-rule remove API.
    new CompositeJavaChaos().clearPlan(container);
  }

  // ==================== Helpers ====================

  private static String readPlanJsonResource(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("planJsonResource");
      final Object v = m.invoke(annotation);
      if (!(v instanceof String s) || s.isBlank()) {
        throw new IllegalArgumentException(
            "planJsonResource() must be a non-blank classpath path");
      }
      return s;
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(
          "annotation " + annotation.annotationType().getName() + " is missing planJsonResource()");
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "failed to read planJsonResource() from " + annotation.annotationType().getName(), e);
    }
  }

  private static String loadResource(final String resource, final Annotation annotation) {
    try (InputStream in =
        JvmPlanTranslator.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException(
            "@" + annotation.annotationType().getSimpleName()
                + " planJsonResource=\"" + resource
                + "\" not found on the test classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new IllegalStateException(
          "failed to read JVM plan resource \"" + resource + "\"", e);
    }
  }
}
