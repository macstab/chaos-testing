/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

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
 * Integration tests for {@link CgroupsDiskChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class CgroupsDiskChaosTest {

  private GenericContainer<?> container;
  private CgroupsDiskChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    container.start();

    chaos = new CgroupsDiskChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should fill disk")
  void shouldFillDisk() throws Exception {
    final String mountPoint = "/tmp";
    final int percentage = 10;

    chaos.fillDisk(container, mountPoint, percentage);

    assertThat(container.execInContainer("ls", "/tmp/chaos-load").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should stress disk")
  void shouldStressDisk() throws Exception {
    final int workers = 2;

    chaos.stressDisk(container, workers);

    assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should reset disk chaos")
  void shouldReset() throws Exception {
    chaos.stressDisk(container, 1);

    chaos.reset(container);

    assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid percentage")
  void shouldRejectInvalidPercentage() throws Exception {
    assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 150))
        .isInstanceOf(ChaosConfigurationException.class)
        .hasMessageContaining("must be in [1, 95]");
  }

  @Test
  @DisplayName("should reject unsafe path")
  void shouldRejectUnsafePath() throws Exception {
    assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp/../etc", 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe characters");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not running");
  }
}
