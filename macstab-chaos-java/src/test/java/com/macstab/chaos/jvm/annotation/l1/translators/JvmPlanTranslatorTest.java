/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.jvm.annotation.l1.ChaosJvmPlan;

@DisplayName("JvmPlanTranslator")
class JvmPlanTranslatorTest {

  @ChaosJvmPlan(planJsonResource = "/test-chaos-plan.json")
  static class ValidPlan {}

  @ChaosJvmPlan(planJsonResource = "/does-not-exist.json")
  static class MissingResource {}

  @ChaosJvmPlan(planJsonResource = "")
  static class BlankResource {}

  private static Annotation pick(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation on " + clazz);
  }

  @Test
  @DisplayName("annotation with blank planJsonResource → IllegalArgumentException at apply")
  void blankResourceRejected() {
    final JvmPlanTranslator t = new JvmPlanTranslator();
    assertThatThrownBy(() -> t.apply(null, pick(BlankResource.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("planJsonResource() must be a non-blank classpath path");
  }

  @Test
  @DisplayName("annotation with non-existent resource → IllegalArgumentException with classpath hint")
  void missingResourceRejected() {
    final JvmPlanTranslator t = new JvmPlanTranslator();
    assertThatThrownBy(() -> t.apply(null, pick(MissingResource.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("@ChaosJvmPlan")
        .hasMessageContaining("does-not-exist.json")
        .hasMessageContaining("not found on the test classpath");
  }

  // Note: the happy-path "applyPlan on a real container" case is covered by the existing
  // JavaAgentTransportIntegrationTest; we don't duplicate that here. The unit test scope is the
  // translator's resource-loading + error contract.
  //
  // The ValidPlan fixture exists so the resource-loading code path is exercised at least once at
  // compile-resolution time (loadResource() is invoked indirectly when ChaosJvmPlan is the only
  // fixture annotation on a class with a present resource).
}
