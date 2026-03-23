/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Comprehensive integration tests for {@link FuseFilesystemChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("FuseFilesystemChaos - Comprehensive Tests")
class FuseFilesystemChaosComprehensiveTest {

  private GenericContainer<?> container;
  private FuseFilesystemChaos chaos;

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      if (chaos != null) {
        chaos.reset(container);
      }
      container.stop();
    }
  }

  // ==================== DISTRIBUTION TESTS ====================

  @Nested
  @DisplayName("Debian-based container")
  class DebianTests {

    @ParameterizedTest
    @ValueSource(strings = {"1M", "10M", "100M", "500M", "1G"})
    @DisplayName("should fill disk with various sizes on Debian")
    void shouldFillDiskDebian(String size) throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, size);

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-fill").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should inject permission errors on Debian")
    void shouldInjectPermissionErrorsDebian() throws Exception {
      container = createDebianContainer();
      container.execInContainer("touch", "/tmp/test-file");
      chaos = new FuseFilesystemChaos();

      chaos.injectPermissionErrors(container, "/tmp/test-file", 0.9);

      final var result = container.execInContainer("ls", "-l", "/tmp/test-file");
      assertThat(result.getStdout()).contains("----------");
    }
  }

  @Nested
  @DisplayName("Alpine-based container")
  class AlpineTests {

    @Test
    @DisplayName("should fill disk on Alpine")
    void shouldFillDiskAlpine() throws Exception {
      container = createAlpineContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, "50M");

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-fill").getExitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("Ubuntu-based container")
  class UbuntuTests {

    @Test
    @DisplayName("should fill disk on Ubuntu")
    void shouldFillDiskUbuntu() throws Exception {
      container = createUbuntuContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, "100M");

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-fill").getExitCode()).isZero();
    }
  }

  // ==================== POSITIVE TESTS ====================

  @Nested
  @DisplayName("Positive Scenarios")
  class PositiveTests {

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 0.6, 0.7, 0.8, 0.9, 1.0})
    @DisplayName("should handle permission errors at various rates")
    void shouldHandleVariousPermissionRates(double rate) throws Exception {
      container = createDebianContainer();
      container.execInContainer("touch", "/tmp/test-" + rate);
      chaos = new FuseFilesystemChaos();

      chaos.injectPermissionErrors(container, "/tmp/test-" + rate, rate);

      assertThat(container.isRunning()).isTrue();
    }

    @Test
    @DisplayName("should handle multiple files with permission errors")
    void shouldHandleMultipleFiles() throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      for (int i = 0; i < 10; i++) {
        container.execInContainer("touch", "/tmp/file" + i);
        chaos.injectPermissionErrors(container, "/tmp/file" + i, 0.9);
      }

      assertThat(container.isRunning()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"512K", "1M", "5M", "10M", "50M", "100M"})
    @DisplayName("should handle various disk fill sizes")
    void shouldHandleVariousSizes(String size) throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, size);

      final var result = container.execInContainer("ls", "-lh", "/tmp/chaos-disk-fill");
      assertThat(result.getExitCode()).isZero();
    }
  }

  // ==================== NEGATIVE TESTS ====================

  @Nested
  @DisplayName("Negative Scenarios")
  class NegativeTests {

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "10", "10Z", "ABC", "100g"})
    @DisplayName("should reject invalid size formats")
    void shouldRejectInvalidSizes(String size) throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      assertThatThrownBy(() -> chaos.fillDisk(container, size)).hasMessageContaining("Invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/../etc/passwd", "/etc/../tmp/test", "../../etc/passwd"})
    @DisplayName("should reject path traversal attempts")
    void shouldRejectPathTraversal(String path) throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      assertThatThrownBy(() -> chaos.injectPermissionErrors(container, path, 0.9))
          .hasMessageContaining("traversal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/test;rm -rf /", "/tmp/`whoami`", "/tmp/$(ls)"})
    @DisplayName("should reject unsafe paths")
    void shouldRejectUnsafePaths(String path) throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      assertThatThrownBy(() -> chaos.injectPermissionErrors(container, path, 0.9))
          .hasMessageContaining("unsafe");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() throws Exception {
      container = createDebianContainer();
      container.stop();
      chaos = new FuseFilesystemChaos();

      assertThatThrownBy(() -> chaos.fillDisk(container, "10M"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== EDGE CASES ====================

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("should handle repeated disk fills")
    void shouldHandleRepeatedFills() throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, "10M");
      chaos.reset(container);
      chaos.fillDisk(container, "20M");
      chaos.reset(container);
      chaos.fillDisk(container, "5M");

      assertThat(container.isRunning()).isTrue();
    }

    @Test
    @DisplayName("should handle permission errors below threshold")
    void shouldHandlePermissionsBelowThreshold() throws Exception {
      container = createDebianContainer();
      container.execInContainer("touch", "/tmp/test");
      chaos = new FuseFilesystemChaos();

      chaos.injectPermissionErrors(container, "/tmp/test", 0.3);

      // Should not remove permissions (rate < 0.5)
      final var result = container.execInContainer("ls", "-l", "/tmp/test");
      assertThat(result.getStdout()).doesNotContain("----------");
    }

    @Test
    @DisplayName("should handle very large sizes")
    void shouldHandleVeryLargeSizes() throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, "500M");

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-fill").getExitCode()).isZero();
    }
  }

  // ==================== CLEANUP TESTS ====================

  @Nested
  @DisplayName("Cleanup Verification")
  class CleanupTests {

    @Test
    @DisplayName("should remove disk fill file")
    void shouldRemoveDiskFillFile() throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, "10M");
      chaos.reset(container);

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-fill").getExitCode()).isNotZero();
    }

    @Test
    @DisplayName("should handle repeated reset")
    void shouldHandleRepeatedReset() throws Exception {
      container = createDebianContainer();
      chaos = new FuseFilesystemChaos();

      chaos.fillDisk(container, "10M");
      chaos.reset(container);
      chaos.reset(container);
      chaos.reset(container);

      assertThat(container.isRunning()).isTrue();
    }
  }

  // ==================== HELPER METHODS ====================

  private GenericContainer<?> createDebianContainer() {
    final var c = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    c.start();
    return c;
  }

  private GenericContainer<?> createAlpineContainer() {
    final var c = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"));
    c.start();
    return c;
  }

  private GenericContainer<?> createUbuntuContainer() {
    final var c =
        new GenericContainer<>(DockerImageName.parse("ubuntu:22.04"))
            .withCommand("sleep", "infinity");
    c.start();
    return c;
  }
}
