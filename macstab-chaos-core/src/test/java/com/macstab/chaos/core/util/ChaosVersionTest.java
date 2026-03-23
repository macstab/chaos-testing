/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChaosVersionTest {

  @Test
  void shouldLoadVersionFromProperties() {
    // When
    final String version = ChaosVersion.get();

    // Then
    assertThat(version).isNotEmpty().isNotEqualTo("unknown");
  }

  @Test
  void shouldFormatDependencyCorrectly() {
    // When
    final String dependency = ChaosVersion.formatDependency("macstab-chaos-cpu");

    // Then
    assertThat(dependency)
        .startsWith("testImplementation(\"com.macstab.chaos:macstab-chaos-cpu:")
        .endsWith("\")");
  }
}
