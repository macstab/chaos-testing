/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Integration tests for {@link CgroupsCpuChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class CgroupsCpuChaosTest {

  private GenericContainer<?> container;
  private CgroupsCpuChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    container.start();

    chaos = new CgroupsCpuChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should throttle CPU")
  void shouldThrottle() throws Exception {
    final int percentage = 50;

    chaos.throttle(container, percentage);

    assertThat(container.execInContainer("pgrep", "cpulimit").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should stress CPU")
  void shouldStress() throws Exception {
    final int workers = 2;

    chaos.stress(container, workers);

    assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should stress CPU with timeout")
  void shouldStressWithTimeout() throws Exception {
    final int workers = 1;
    final Duration duration = Duration.ofSeconds(5);

    chaos.stress(container, workers, duration);

    assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should reset CPU chaos")
  void shouldReset() throws Exception {
    chaos.stress(container, 1);

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
    assertThatThrownBy(() -> chaos.throttle(container, 150))
        .isInstanceOf(ChaosConfigurationException.class)
        .hasMessageContaining("must be in [1, 100]");
  }

  @Test
  @DisplayName("should reject invalid workers")
  void shouldRejectInvalidWorkers() throws Exception {
    assertThatThrownBy(() -> chaos.stress(container, 0))
        .isInstanceOf(ChaosConfigurationException.class)
        .hasMessageContaining("must be >= 1");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.throttle(container, 50))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not running");
  }
}
