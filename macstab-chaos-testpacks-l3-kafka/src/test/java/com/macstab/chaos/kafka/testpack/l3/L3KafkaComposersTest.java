/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;

/**
 * Verifies that every Kafka L3 incident annotation carries the {@link ChaosL3} meta-annotation with
 * a non-blank composer class name.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class L3KafkaComposersTest {

  @Test
  void kafkaBrokerFailure_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosKafkaBrokerFailure.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void kafkaConsumerRebalance_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosKafkaConsumerRebalance.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void kafkaNetworkDegradation_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosKafkaNetworkDegradation.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void kafkaClockDrift_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosKafkaClockDrift.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void kafkaStoragePressure_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosKafkaStoragePressure.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void kafkaZookeeperLoss_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosKafkaZookeeperLoss.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void kafkaZookeeperLoss_severityIsCritical() {
    final ChaosL3 l3 = IncidentChaosKafkaZookeeperLoss.class.getAnnotation(ChaosL3.class);
    assertThat(l3.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.CRITICAL);
  }

  @Test
  void kafkaZookeeperLoss_isRepeatable() {
    assertThat(
            IncidentChaosKafkaZookeeperLoss.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  @Test
  void kafkaUncleanLeaderElection_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosKafkaUncleanLeaderElection.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void kafkaUncleanLeaderElection_severityIsCritical() {
    final ChaosL3 l3 = IncidentChaosKafkaUncleanLeaderElection.class.getAnnotation(ChaosL3.class);
    assertThat(l3.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.CRITICAL);
  }

  @Test
  void kafkaUncleanLeaderElection_isRepeatable() {
    assertThat(
            IncidentChaosKafkaUncleanLeaderElection.class.isAnnotationPresent(
                java.lang.annotation.Repeatable.class))
        .isTrue();
  }

  @Test
  void allAnnotations_composerClassNameMatchesExpectedPackage() {
    assertThat(IncidentChaosKafkaBrokerFailure.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.kafka.testpack.l3.composers.");
    assertThat(IncidentChaosKafkaConsumerRebalance.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.kafka.testpack.l3.composers.");
    assertThat(IncidentChaosKafkaNetworkDegradation.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.kafka.testpack.l3.composers.");
    assertThat(IncidentChaosKafkaClockDrift.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.kafka.testpack.l3.composers.");
    assertThat(IncidentChaosKafkaStoragePressure.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.kafka.testpack.l3.composers.");
    assertThat(IncidentChaosKafkaZookeeperLoss.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.kafka.testpack.l3.composers.");
    assertThat(
            IncidentChaosKafkaUncleanLeaderElection.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.kafka.testpack.l3.composers.");
  }
}
