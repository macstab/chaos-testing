/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.grpc.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;

/**
 * Structural contract tests for all gRPC L3 incident scenario annotations.
 *
 * <p>Verifies that every annotation carries the required {@link ChaosL3} meta-annotation and
 * declares a non-blank composer class name.
 */
class L3GrpcComposersTest {

  @Test
  void eachAnnotationHasChaosL3MetaAnnotation() {
    final List<Class<?>> annotations =
        List.of(
            IncidentChaosGrpcDeadlinePropagation.class,
            IncidentChaosGrpcConnectionDrain.class,
            IncidentChaosGrpcLoadBalancingFailure.class,
            IncidentChaosGrpcGoawayStorm.class);

    for (final Class<?> ann : annotations) {
      assertThat(ann.isAnnotationPresent(ChaosL3.class))
          .as(ann.getSimpleName() + " must carry @ChaosL3")
          .isTrue();
    }
  }

  @Test
  void composerClassNamesAreNonBlank() {
    final List<Class<?>> annotations =
        List.of(
            IncidentChaosGrpcDeadlinePropagation.class,
            IncidentChaosGrpcConnectionDrain.class,
            IncidentChaosGrpcLoadBalancingFailure.class,
            IncidentChaosGrpcGoawayStorm.class);

    for (final Class<?> ann : annotations) {
      final ChaosL3 meta = ann.getAnnotation(ChaosL3.class);
      assertThat(meta.composer())
          .as(ann.getSimpleName() + " composer must not be blank")
          .isNotBlank();
    }
  }

  @Test
  void eachAnnotationIsRepeatable() {
    final List<Class<?>> annotations =
        List.of(
            IncidentChaosGrpcDeadlinePropagation.class,
            IncidentChaosGrpcConnectionDrain.class,
            IncidentChaosGrpcLoadBalancingFailure.class,
            IncidentChaosGrpcGoawayStorm.class);

    for (final Class<?> ann : annotations) {
      assertThat(ann.getAnnotation(java.lang.annotation.Repeatable.class))
          .as(ann.getSimpleName() + " must be @Repeatable")
          .isNotNull();
    }
  }

  @Test
  void deadlinePropagationHasCriticalSeverity() {
    final ChaosL3 meta = IncidentChaosGrpcDeadlinePropagation.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.CRITICAL);
  }

  @Test
  void connectionDrainHasSevereSeverity() {
    final ChaosL3 meta = IncidentChaosGrpcConnectionDrain.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.SEVERE);
  }

  @Test
  void loadBalancingFailureHasSevereSeverity() {
    final ChaosL3 meta = IncidentChaosGrpcLoadBalancingFailure.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.SEVERE);
  }

  @Test
  void goawayStormHasSevereSeverity() {
    final ChaosL3 meta = IncidentChaosGrpcGoawayStorm.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.SEVERE);
  }

  @Test
  void composerNamesMatchExpectedPattern() {
    assertThat(IncidentChaosGrpcDeadlinePropagation.class.getAnnotation(ChaosL3.class).composer())
        .endsWith("GrpcDeadlinePropagationComposer");
    assertThat(IncidentChaosGrpcConnectionDrain.class.getAnnotation(ChaosL3.class).composer())
        .endsWith("GrpcConnectionDrainComposer");
    assertThat(IncidentChaosGrpcLoadBalancingFailure.class.getAnnotation(ChaosL3.class).composer())
        .endsWith("GrpcLoadBalancingFailureComposer");
    assertThat(IncidentChaosGrpcGoawayStorm.class.getAnnotation(ChaosL3.class).composer())
        .endsWith("GrpcGoawayStormComposer");
  }
}
