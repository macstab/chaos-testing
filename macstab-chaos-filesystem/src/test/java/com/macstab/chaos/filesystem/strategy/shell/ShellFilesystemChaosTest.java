/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link ShellFilesystemChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ShellFilesystemChaosTest {

  private GenericContainer<?> container;
  private ShellFilesystemChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    container.start();

    chaos = new ShellFilesystemChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should fill disk with garbage data")
  void shouldFillDisk() throws Exception {
    final String size = "10M";

    chaos.fillDisk(container, size);

    final var result = container.execInContainer("ls", "-lh", "/tmp/chaos-disk-fill");
    assertThat(result.getExitCode()).isZero();
    assertThat(result.getStdout()).contains("10M");
  }

  @Test
  @DisplayName("should inject permission errors")
  void shouldInjectPermissionErrors() throws Exception {
    // Create test file
    container.execInContainer("touch", "/tmp/test-file");

    final double rate = 0.8;

    chaos.injectPermissionErrors(container, "/tmp/test-file", rate);

    // Verify permissions removed
    final var result = container.execInContainer("ls", "-l", "/tmp/test-file");
    assertThat(result.getStdout()).contains("----------");
  }

  @Test
  @DisplayName("should reset chaos")
  void shouldReset() throws Exception {
    chaos.fillDisk(container, "5M");

    chaos.reset(container);

    final var result = container.execInContainer("ls", "/tmp/chaos-disk-fill");
    assertThat(result.getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid size format")
  void shouldRejectInvalidSize() throws Exception {
    assertThatThrownBy(() -> chaos.fillDisk(container, "invalid"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid size format");
  }

  @Test
  @DisplayName("should reject invalid permission rate")
  void shouldRejectInvalidRate() throws Exception {
    assertThatThrownBy(() -> chaos.injectPermissionErrors(container, "/tmp/test", 1.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in [0.0, 1.0]");
  }

  @Test
  @DisplayName("should reject unsafe path")
  void shouldRejectUnsafePath() throws Exception {
    assertThatThrownBy(() -> chaos.injectPermissionErrors(container, "/tmp/../etc/passwd", 0.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Path traversal");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.fillDisk(container, "10M"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be running");
  }

  @Test
  @DisplayName("should handle large size values")
  void shouldHandleLargeSizes() throws Exception {
    chaos.fillDisk(container, "100M");

    final var result = container.execInContainer("ls", "-lh", "/tmp/chaos-disk-fill");
    assertThat(result.getStdout()).contains("100M");
  }

  @Test
  @DisplayName("should handle kilobyte sizes")
  void shouldHandleKilobyteSize() throws Exception {
    chaos.fillDisk(container, "512K");

    final var result = container.execInContainer("ls", "-lh", "/tmp/chaos-disk-fill");
    assertThat(result.getExitCode()).isZero();
  }
}
