/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * Verifies that every Kubernetes L3 incident annotation carries the {@link ChaosL3} meta-annotation
 * with a non-blank composer class name and the expected severity.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class L3KubernetesComposersTest {

  // --- IncidentChaosK8sRollingUpdateRst ---

  @Test
  void rollingUpdateRst_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosK8sRollingUpdateRst.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void rollingUpdateRst_severityIsCritical() {
    final var meta = IncidentChaosK8sRollingUpdateRst.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void rollingUpdateRst_composerIsNonBlank() {
    final var meta = IncidentChaosK8sRollingUpdateRst.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void rollingUpdateRst_isRepeatable() {
    assertThat(
            IncidentChaosK8sRollingUpdateRst.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosK8sDnsNdots5Storm ---

  @Test
  void dnsNdots5Storm_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosK8sDnsNdots5Storm.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void dnsNdots5Storm_severityIsSevere() {
    final var meta = IncidentChaosK8sDnsNdots5Storm.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void dnsNdots5Storm_composerIsNonBlank() {
    final var meta = IncidentChaosK8sDnsNdots5Storm.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void dnsNdots5Storm_isRepeatable() {
    assertThat(
            IncidentChaosK8sDnsNdots5Storm.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosK8sOomKillMidGc ---

  @Test
  void oomKillMidGc_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosK8sOomKillMidGc.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void oomKillMidGc_severityIsCritical() {
    final var meta = IncidentChaosK8sOomKillMidGc.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void oomKillMidGc_composerIsNonBlank() {
    final var meta = IncidentChaosK8sOomKillMidGc.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void oomKillMidGc_isRepeatable() {
    assertThat(
            IncidentChaosK8sOomKillMidGc.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosK8sSidecarShutdownRace ---

  @Test
  void sidecarShutdownRace_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosK8sSidecarShutdownRace.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void sidecarShutdownRace_severityIsSevere() {
    final var meta = IncidentChaosK8sSidecarShutdownRace.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void sidecarShutdownRace_composerIsNonBlank() {
    final var meta = IncidentChaosK8sSidecarShutdownRace.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void sidecarShutdownRace_isRepeatable() {
    assertThat(
            IncidentChaosK8sSidecarShutdownRace.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- IncidentChaosK8sCpuThrottleGcAmplification ---

  @Test
  void cpuThrottleGcAmplification_hasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosK8sCpuThrottleGcAmplification.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void cpuThrottleGcAmplification_severityIsCritical() {
    final var meta = IncidentChaosK8sCpuThrottleGcAmplification.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void cpuThrottleGcAmplification_composerIsNonBlank() {
    final var meta = IncidentChaosK8sCpuThrottleGcAmplification.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void cpuThrottleGcAmplification_isRepeatable() {
    assertThat(
            IncidentChaosK8sCpuThrottleGcAmplification.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // --- cross-cutting ---

  @Test
  void allAnnotationsHaveIdAttribute() throws Exception {
    assertThat(IncidentChaosK8sRollingUpdateRst.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosK8sDnsNdots5Storm.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosK8sOomKillMidGc.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosK8sSidecarShutdownRace.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosK8sCpuThrottleGcAmplification.class.getDeclaredMethod("id"))
        .isNotNull();
  }

  @Test
  void allComposerClassNamesReferenceCorrectPackage() {
    final String expectedPackage = "com.macstab.chaos.kubernetes.testpack.l3.composers.";
    assertThat(IncidentChaosK8sRollingUpdateRst.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosK8sDnsNdots5Storm.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosK8sOomKillMidGc.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosK8sSidecarShutdownRace.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(
            IncidentChaosK8sCpuThrottleGcAmplification.class
                .getAnnotation(ChaosL3.class)
                .composer())
        .startsWith(expectedPackage);
  }
}
