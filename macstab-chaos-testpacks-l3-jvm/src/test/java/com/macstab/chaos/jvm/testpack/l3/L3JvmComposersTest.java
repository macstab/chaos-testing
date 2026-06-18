/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * Verifies that every JVM L3 incident annotation carries the {@link ChaosL3} meta-annotation with a
 * non-blank composer class name and the expected severity.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class L3JvmComposersTest {

  // --- IncidentChaosJvmSafepointCascade ---

  @Test
  void safepointCascade_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmSafepointCascade.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void safepointCascade_severityIsCritical() {
    final var meta = IncidentChaosJvmSafepointCascade.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void safepointCascade_composerIsNonBlank() {
    final var meta = IncidentChaosJvmSafepointCascade.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void safepointCascade_isRepeatable() {
    assertThat(
            IncidentChaosJvmSafepointCascade.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosJvmCodeCacheFull ---

  @Test
  void codeCacheFull_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmCodeCacheFull.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void codeCacheFull_severityIsCritical() {
    final var meta = IncidentChaosJvmCodeCacheFull.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void codeCacheFull_composerIsNonBlank() {
    final var meta = IncidentChaosJvmCodeCacheFull.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void codeCacheFull_isRepeatable() {
    assertThat(
            IncidentChaosJvmCodeCacheFull.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosJvmCarrierPinning ---

  @Test
  void carrierPinning_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmCarrierPinning.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void carrierPinning_severityIsCritical() {
    final var meta = IncidentChaosJvmCarrierPinning.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void carrierPinning_composerIsNonBlank() {
    final var meta = IncidentChaosJvmCarrierPinning.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void carrierPinning_isRepeatable() {
    assertThat(
            IncidentChaosJvmCarrierPinning.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosJvmMetaspaceGlacier ---

  @Test
  void metaspaceGlacier_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmMetaspaceGlacier.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void metaspaceGlacier_severityIsSevere() {
    final var meta = IncidentChaosJvmMetaspaceGlacier.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void metaspaceGlacier_composerIsNonBlank() {
    final var meta = IncidentChaosJvmMetaspaceGlacier.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void metaspaceGlacier_isRepeatable() {
    assertThat(
            IncidentChaosJvmMetaspaceGlacier.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosJvmG1ToSpaceExhausted ---

  @Test
  void g1ToSpaceExhausted_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmG1ToSpaceExhausted.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void g1ToSpaceExhausted_severityIsCritical() {
    final var meta = IncidentChaosJvmG1ToSpaceExhausted.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void g1ToSpaceExhausted_composerIsNonBlank() {
    final var meta = IncidentChaosJvmG1ToSpaceExhausted.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void g1ToSpaceExhausted_isRepeatable() {
    assertThat(
            IncidentChaosJvmG1ToSpaceExhausted.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosJvmGcLockerFakeOom ---

  @Test
  void gcLockerFakeOom_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmGcLockerFakeOom.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void gcLockerFakeOom_severityIsSevere() {
    final var meta = IncidentChaosJvmGcLockerFakeOom.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void gcLockerFakeOom_composerIsNonBlank() {
    final var meta = IncidentChaosJvmGcLockerFakeOom.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void gcLockerFakeOom_isRepeatable() {
    assertThat(
            IncidentChaosJvmGcLockerFakeOom.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosJvmDeoptimizationStorm ---

  @Test
  void deoptimizationStorm_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmDeoptimizationStorm.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void deoptimizationStorm_severityIsSevere() {
    final var meta = IncidentChaosJvmDeoptimizationStorm.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void deoptimizationStorm_composerIsNonBlank() {
    final var meta = IncidentChaosJvmDeoptimizationStorm.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void deoptimizationStorm_isRepeatable() {
    assertThat(
            IncidentChaosJvmDeoptimizationStorm.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosJvmDirectMemoryLeak ---

  @Test
  void directMemoryLeak_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosJvmDirectMemoryLeak.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void directMemoryLeak_severityIsSevere() {
    final var meta = IncidentChaosJvmDirectMemoryLeak.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void directMemoryLeak_composerIsNonBlank() {
    final var meta = IncidentChaosJvmDirectMemoryLeak.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void directMemoryLeak_isRepeatable() {
    assertThat(
            IncidentChaosJvmDirectMemoryLeak.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- cross-cutting ---

  @Test
  void allAnnotationsHaveIdAttribute() throws Exception {
    assertThat(IncidentChaosJvmSafepointCascade.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosJvmCodeCacheFull.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosJvmCarrierPinning.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosJvmMetaspaceGlacier.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosJvmG1ToSpaceExhausted.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosJvmGcLockerFakeOom.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosJvmDeoptimizationStorm.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosJvmDirectMemoryLeak.class.getDeclaredMethod("id")).isNotNull();
  }

  @Test
  void allComposerClassNamesReferenceCorrectPackage() {
    final String expectedPackage = "com.macstab.chaos.jvm.testpack.l3.composers.";
    assertThat(IncidentChaosJvmSafepointCascade.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosJvmCodeCacheFull.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosJvmCarrierPinning.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosJvmMetaspaceGlacier.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosJvmG1ToSpaceExhausted.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosJvmGcLockerFakeOom.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosJvmDeoptimizationStorm.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosJvmDirectMemoryLeak.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
  }
}
