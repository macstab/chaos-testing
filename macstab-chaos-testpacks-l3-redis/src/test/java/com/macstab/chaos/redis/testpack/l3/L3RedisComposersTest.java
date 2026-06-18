/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

class L3RedisComposersTest {

  @Test
  void redisFailoverStormHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosRedisFailoverStorm.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void redisFailoverStormSeverityIsCritical() {
    final var meta = IncidentChaosRedisFailoverStorm.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void redisFailoverStormComposerIsNonBlank() {
    final var meta = IncidentChaosRedisFailoverStorm.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void redisFailoverStormIsRepeatable() {
    assertThat(
            IncidentChaosRedisFailoverStorm.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void redisCacheAvalancheHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosRedisCacheAvalanche.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void redisCacheAvalancheSeverityIsCritical() {
    final var meta = IncidentChaosRedisCacheAvalanche.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void redisCacheAvalancheComposerIsNonBlank() {
    final var meta = IncidentChaosRedisCacheAvalanche.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void redisCacheAvalancheIsRepeatable() {
    assertThat(
            IncidentChaosRedisCacheAvalanche.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void redisClockDriftHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosRedisClockDrift.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void redisClockDriftSeverityIsModerate() {
    final var meta = IncidentChaosRedisClockDrift.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
  }

  @Test
  void redisClockDriftComposerIsNonBlank() {
    final var meta = IncidentChaosRedisClockDrift.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void redisClockDriftIsRepeatable() {
    assertThat(
            IncidentChaosRedisClockDrift.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void redisOomEvictionHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosRedisOomEviction.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void redisOomEvictionSeverityIsSevere() {
    final var meta = IncidentChaosRedisOomEviction.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
  }

  @Test
  void redisOomEvictionComposerIsNonBlank() {
    final var meta = IncidentChaosRedisOomEviction.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void redisOomEvictionIsRepeatable() {
    assertThat(
            IncidentChaosRedisOomEviction.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void redisSlowlogHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosRedisSlowlog.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void redisSlowlogSeverityIsModerate() {
    final var meta = IncidentChaosRedisSlowlog.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
  }

  @Test
  void redisSlowlogComposerIsNonBlank() {
    final var meta = IncidentChaosRedisSlowlog.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void redisSlowlogIsRepeatable() {
    assertThat(
            IncidentChaosRedisSlowlog.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void redisNetworkFlapHasChaosL3MetaAnnotation() {
    assertThat(IncidentChaosRedisNetworkFlap.class.isAnnotationPresent(ChaosL3.class)).isTrue();
  }

  @Test
  void redisNetworkFlapSeverityIsCritical() {
    final var meta = IncidentChaosRedisNetworkFlap.class.getAnnotation(ChaosL3.class);
    assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void redisNetworkFlapComposerIsNonBlank() {
    final var meta = IncidentChaosRedisNetworkFlap.class.getAnnotation(ChaosL3.class);
    assertThat(meta.composer()).isNotBlank();
  }

  @Test
  void redisNetworkFlapIsRepeatable() {
    assertThat(
            IncidentChaosRedisNetworkFlap.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  // ---

  @Test
  void allAnnotationsHaveIdAttribute() throws Exception {
    assertThat(IncidentChaosRedisFailoverStorm.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosRedisCacheAvalanche.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosRedisClockDrift.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosRedisOomEviction.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosRedisSlowlog.class.getDeclaredMethod("id")).isNotNull();
    assertThat(IncidentChaosRedisNetworkFlap.class.getDeclaredMethod("id")).isNotNull();
  }

  @Test
  void allComposerClassNamesReferenceCorrectPackage() {
    final String expectedPackage = "com.macstab.chaos.redis.testpack.l3.composers.";
    assertThat(IncidentChaosRedisFailoverStorm.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosRedisCacheAvalanche.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosRedisClockDrift.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosRedisOomEviction.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosRedisSlowlog.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
    assertThat(IncidentChaosRedisNetworkFlap.class.getAnnotation(ChaosL3.class).composer())
        .startsWith(expectedPackage);
  }
}
