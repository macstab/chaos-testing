/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * Verifies that every cache L3 incident annotation carries the {@link ChaosL3} meta-annotation with
 * a non-blank composer class name and the expected severity.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class L3CacheComposersTest {

  // --- IncidentChaosCacheStampede ---

  @Test
  void cacheStampede_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosCacheStampede.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void cacheStampede_severityIsCritical() {
    final var meta = IncidentChaosCacheStampede.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void cacheStampede_composerIsNonBlank() {
    final var meta = IncidentChaosCacheStampede.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void cacheStampede_isRepeatable() {
    assertThat(
            IncidentChaosCacheStampede.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosCacheWarmingFailure ---

  @Test
  void cacheWarmingFailure_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosCacheWarmingFailure.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void cacheWarmingFailure_severityIsCritical() {
    final var meta = IncidentChaosCacheWarmingFailure.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void cacheWarmingFailure_composerIsNonBlank() {
    final var meta = IncidentChaosCacheWarmingFailure.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void cacheWarmingFailure_isRepeatable() {
    assertThat(
            IncidentChaosCacheWarmingFailure.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosCacheSerializationMismatch ---

  @Test
  void cacheSerializationMismatch_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosCacheSerializationMismatch.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void cacheSerializationMismatch_severityIsSevere() {
    final var meta = IncidentChaosCacheSerializationMismatch.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void cacheSerializationMismatch_composerIsNonBlank() {
    final var meta = IncidentChaosCacheSerializationMismatch.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void cacheSerializationMismatch_isRepeatable() {
    assertThat(
            IncidentChaosCacheSerializationMismatch.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosCaffeineEvictionDeadlock ---

  @Test
  void caffeineEvictionDeadlock_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosCaffeineEvictionDeadlock.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void caffeineEvictionDeadlock_severityIsSevere() {
    final var meta = IncidentChaosCaffeineEvictionDeadlock.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void caffeineEvictionDeadlock_composerIsNonBlank() {
    final var meta = IncidentChaosCaffeineEvictionDeadlock.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void caffeineEvictionDeadlock_isRepeatable() {
    assertThat(
            IncidentChaosCaffeineEvictionDeadlock.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosHazelcastSplitBrain ---

  @Test
  void hazelcastSplitBrain_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosHazelcastSplitBrain.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void hazelcastSplitBrain_severityIsCritical() {
    final var meta = IncidentChaosHazelcastSplitBrain.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void hazelcastSplitBrain_composerIsNonBlank() {
    final var meta = IncidentChaosHazelcastSplitBrain.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void hazelcastSplitBrain_isRepeatable() {
    assertThat(
            IncidentChaosHazelcastSplitBrain.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- cross-cutting ---

  @Test
  void allAnnotationsHaveIdAttribute() throws Exception {
    assertThat(IncidentChaosCacheStampede.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosCacheWarmingFailure.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosCacheSerializationMismatch.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosCaffeineEvictionDeadlock.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosHazelcastSplitBrain.class.getDeclaredMethod("id")).isNotNull();
  }

  @Test
  void allComposerClassNamesReferenceCorrectPackage() {
    final String expectedPackage = "com.macstab.chaos.cache.testpack.l3.composers.";
    assertThat(IncidentChaosCacheStampede.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosCacheWarmingFailure.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(
            IncidentChaosCacheSerializationMismatch.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosCaffeineEvictionDeadlock.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosHazelcastSplitBrain.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
  }
}
