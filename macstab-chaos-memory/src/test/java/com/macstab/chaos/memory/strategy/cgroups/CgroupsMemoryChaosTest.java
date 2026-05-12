/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.cgroups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Integration tests for {@link CgroupsMemoryChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class CgroupsMemoryChaosTest {

  private GenericContainer<?> container;
  private CgroupsMemoryChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    container.start();

    chaos = new CgroupsMemoryChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should stress memory")
  void shouldStress() throws Exception {
    final String size = "100M";

    chaos.stress(container, size);

    assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should set memory pressure")
  void shouldSetPressure() throws Exception {
    final String threshold = "50M";

    chaos.setPressure(container, threshold);

    assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should reset memory chaos")
  void shouldReset() throws Exception {
    chaos.stress(container, "50M");

    chaos.reset(container);

    assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid size format")
  void shouldRejectInvalidSize() throws Exception {
    assertThatThrownBy(() -> chaos.stress(container, "invalid"))
        .isInstanceOf(ChaosConfigurationException.class)
        .hasMessageContaining("Invalid size format");
  }

  @Test
  @DisplayName("should reject too large size")
  void shouldRejectTooLarge() throws Exception {
    assertThatThrownBy(() -> chaos.stress(container, "200000G"))
        .isInstanceOf(ChaosConfigurationException.class)
        .hasMessageContaining("too large");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.stress(container, "100M"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not running");
  }
}
