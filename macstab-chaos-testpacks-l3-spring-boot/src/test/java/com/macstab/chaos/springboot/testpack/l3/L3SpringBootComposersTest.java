/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;

/**
 * Verifies that every Spring Boot L3 incident annotation carries the {@link ChaosL3}
 * meta-annotation with a non-blank composer class name.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class L3SpringBootComposersTest {

  @Test
  void springStartupFailure_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosSpringStartupFailure.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void springGracefulShutdown_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosSpringGracefulShutdown.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void springMemoryCrisis_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosSpringMemoryCrisis.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void springConfigServerDown_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosSpringConfigServerDown.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void springDatabaseOutage_hasChaosL3WithComposer() {
    final ChaosL3 l3 = IncidentChaosSpringDatabaseOutage.class.getAnnotation(ChaosL3.class);
    assertThat(l3).isNotNull();
    assertThat(l3.composer()).isNotBlank();
  }

  @Test
  void allAnnotations_composerClassNameMatchesExpectedPackage() {
    assertThat(IncidentChaosSpringStartupFailure.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.springboot.testpack.l3.composers.");
    assertThat(IncidentChaosSpringGracefulShutdown.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.springboot.testpack.l3.composers.");
    assertThat(IncidentChaosSpringMemoryCrisis.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.springboot.testpack.l3.composers.");
    assertThat(IncidentChaosSpringConfigServerDown.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.springboot.testpack.l3.composers.");
    assertThat(IncidentChaosSpringDatabaseOutage.class.getAnnotation(ChaosL3.class).composer())
        .startsWith("com.macstab.chaos.springboot.testpack.l3.composers.");
  }
}
