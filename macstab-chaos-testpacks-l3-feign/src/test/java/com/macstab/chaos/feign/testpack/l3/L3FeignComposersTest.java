/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

class L3FeignComposersTest {

  @Test
  void feignHystrixThreadLeakHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosFeignHystrixThreadLeak.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void feignHystrixThreadLeakSeverityIsCritical() {
    final var meta = IncidentChaosFeignHystrixThreadLeak.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void feignHystrixThreadLeakComposerIsNonBlank() {
    final var meta = IncidentChaosFeignHystrixThreadLeak.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void feignHystrixThreadLeakIsRepeatable() {
    assertThat(
            IncidentChaosFeignHystrixThreadLeak.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void feignRetryAmplificationHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosFeignRetryAmplification.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void feignRetryAmplificationSeverityIsSevere() {
    final var meta = IncidentChaosFeignRetryAmplification.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void feignRetryAmplificationComposerIsNonBlank() {
    final var meta = IncidentChaosFeignRetryAmplification.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void feignRetryAmplificationIsRepeatable() {
    assertThat(
            IncidentChaosFeignRetryAmplification.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void feignStaleLoadBalancerHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosFeignStaleLoadBalancer.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void feignStaleLoadBalancerSeverityIsModerate() {
    final var meta = IncidentChaosFeignStaleLoadBalancer.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
  }

  @Test
  void feignStaleLoadBalancerComposerIsNonBlank() {
    final var meta = IncidentChaosFeignStaleLoadBalancer.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void feignStaleLoadBalancerIsRepeatable() {
    assertThat(
            IncidentChaosFeignStaleLoadBalancer.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void feignChunkedConnectionLeakHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosFeignChunkedConnectionLeak.class.isAnnotationPresent(ChaosL3.class))
        .isTrue();
  }

  @Test
  void feignChunkedConnectionLeakSeverityIsSevere() {
    final var meta = IncidentChaosFeignChunkedConnectionLeak.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void feignChunkedConnectionLeakComposerIsNonBlank() {
    final var meta = IncidentChaosFeignChunkedConnectionLeak.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void feignChunkedConnectionLeakIsRepeatable() {
    assertThat(
            IncidentChaosFeignChunkedConnectionLeak.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void okHttpMetastablePoolHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosOkHttpMetastablePool.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void okHttpMetastablePoolSeverityIsSevere() {
    final var meta = IncidentChaosOkHttpMetastablePool.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void okHttpMetastablePoolComposerIsNonBlank() {
    final var meta = IncidentChaosOkHttpMetastablePool.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void okHttpMetastablePoolIsRepeatable() {
    assertThat(
            IncidentChaosOkHttpMetastablePool.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void allAnnotationsHaveIdAttribute() throws Exception {
    assertThat(IncidentChaosFeignHystrixThreadLeak.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosFeignRetryAmplification.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosFeignStaleLoadBalancer.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosFeignChunkedConnectionLeak.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosOkHttpMetastablePool.class.getDeclaredMethod("id")).isNotNull();
  }

  @Test
  void allComposerClassNamesReferenceCorrectPackage() {
    final String expectedPackage = "com.macstab.chaos.feign.testpack.l3.composers.";
    assertThat(IncidentChaosFeignHystrixThreadLeak.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosFeignRetryAmplification.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosFeignStaleLoadBalancer.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(
            IncidentChaosFeignChunkedConnectionLeak.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosOkHttpMetastablePool.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
  }
}
