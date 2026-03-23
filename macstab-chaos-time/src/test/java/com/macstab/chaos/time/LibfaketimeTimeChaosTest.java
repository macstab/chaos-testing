/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link LibfaketimeTimeChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class LibfaketimeTimeChaosTest {

  private GenericContainer<?> container;
  private LibfaketimeTimeChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    LibfaketimeTimeChaos.enableDynamicTime(container);
    container.start();

    chaos = new LibfaketimeTimeChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should shift time forward")
  void shouldShiftTimeForward() throws Exception {
    final Duration offset = Duration.ofHours(5);

    chaos.shift(container, offset);

    final var result = container.execInContainer("cat", "/tmp/faketime");
    assertThat(result.getStdout().trim()).isEqualTo("+5h");
  }

  @Test
  @DisplayName("should shift time backward")
  void shouldShiftTimeBackward() throws Exception {
    final Duration offset = Duration.ofDays(-1);

    chaos.shift(container, offset);

    final var result = container.execInContainer("cat", "/tmp/faketime");
    assertThat(result.getStdout().trim()).isEqualTo("-1d");
  }

  @Test
  @DisplayName("should set time drift")
  void shouldSetTimeDrift() throws Exception {
    final double speedMultiplier = 2.0;

    chaos.drift(container, speedMultiplier);

    final var result = container.execInContainer("cat", "/tmp/faketime");
    assertThat(result.getStdout().trim()).isEqualTo("x2.0");
  }

  @Test
  @DisplayName("should reset time chaos")
  void shouldReset() throws Exception {
    chaos.shift(container, Duration.ofHours(3));

    chaos.reset(container);

    final var result = container.execInContainer("ls", "/tmp/faketime");
    assertThat(result.getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid speed multiplier")
  void shouldRejectInvalidSpeed() throws Exception {
    assertThatThrownBy(() -> chaos.drift(container, -1.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be > 0.0");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.shift(container, Duration.ofHours(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be running");
  }
}
