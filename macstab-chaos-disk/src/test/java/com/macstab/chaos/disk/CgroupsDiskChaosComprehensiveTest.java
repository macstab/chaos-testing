/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.core.exception.ChaosConfigurationException;
import java.time.Duration;
import org.awaitility.Awaitility;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Comprehensive parametrized integration tests for {@link CgroupsDiskChaos}.
 *
 * <p>Covers multiple images (Debian-based {@code redis:7.4} and Alpine-based {@code
 * redis:7.4-alpine}), parametrized fill percentages, parametrized worker counts, fill-by-size
 * variants, and cleanup verification.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsDiskChaos — comprehensive")
class CgroupsDiskChaosComprehensiveTest {

  private GenericContainer<?> container;
  private CgroupsDiskChaos chaos;

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      if (chaos != null) chaos.reset(container);
      container.stop();
    }
  }

  // ==================== Debian (glibc) ====================

  @Nested
  @DisplayName("Debian/glibc container")
  class DebianTests {

    @ParameterizedTest
    @ValueSource(ints = {5, 10, 25, 50, 75, 90})
    @DisplayName("fillDisk — creates chaos-disk-load at each percentage")
    void shouldFillDisk(final int percentage) throws Exception {
      // Use a 512 MB tmpfs so percentage fills stay bounded (≤ 460 MB at 90%).
      // Without tmpfs, the overlay filesystem total on Docker Desktop is ~60 GB and
      // a 10% fill would write 6 GB via dd — unacceptable for CI.
      container = startDebianWithTmpFs();
      chaos = new CgroupsDiskChaos();

      chaos.fillDisk(container, "/tmp", percentage);

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-load").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    @DisplayName("stressDisk — stress-ng hdd workers detected via /proc/comm")
    void shouldStressDisk(final int workers) {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      chaos.stressDisk(container, workers);

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5M", "10M", "50M"})
    @DisplayName("fillDiskBySize — creates chaos-disk-load at each size")
    void shouldFillDiskBySize(final String size) throws Exception {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      chaos.fillDiskBySize(container, "/tmp", size);

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-load").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    @DisplayName("stressDisk(duration) — timed stress workers start without error")
    void shouldStressDiskWithDuration(final int workers) {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      chaos.stressDisk(container, workers, Duration.ofSeconds(60));

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());
    }

    @Test
    @DisplayName("getDiskUsagePercent — returns value in [0, 100] for /tmp")
    void shouldGetDiskUsagePercent() {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      final int usage = chaos.getDiskUsagePercent(container, "/tmp");

      assertThat(usage).isBetween(0, 100);
    }
  }

  // ==================== Alpine (musl) ====================

  @Nested
  @DisplayName("Alpine/musl container")
  class AlpineTests {

    @Test
    @DisplayName("fillDisk — creates chaos-disk-load on Alpine")
    void shouldFillDiskOnAlpine() throws Exception {
      container = startAlpineWithTmpFs();
      chaos = new CgroupsDiskChaos();

      chaos.fillDisk(container, "/tmp", 20);

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-load").getExitCode()).isZero();
    }

    @Test
    @DisplayName("getDiskUsagePercent — returns valid range on Alpine")
    void shouldGetDiskUsageOnAlpine() {
      container = startAlpine();
      chaos = new CgroupsDiskChaos();

      final int usage = chaos.getDiskUsagePercent(container, "/tmp");

      assertThat(usage).isBetween(0, 100);
    }
  }

  // ==================== Negative / validation ====================

  @Nested
  @DisplayName("Negative tests")
  class NegativeTests {

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 96, 100, 150})
    @DisplayName("fillDisk — invalid percentages throw ChaosConfigurationException")
    void shouldRejectInvalidPercentage(final int percentage) {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", percentage))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("must be in [1, 95]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp;rm -rf /", "/tmp|cat /etc/passwd", "/tmp`id`"})
    @DisplayName("fillDisk — shell-injection paths throw ChaosConfigurationException (unsafe chars)")
    void shouldRejectShellInjectionPaths(final String path) {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      assertThatThrownBy(() -> chaos.fillDisk(container, path, 10))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("unsafe characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/../etc", "../../etc/passwd", "/var/../etc/shadow"})
    @DisplayName("fillDisk — path traversal throws ChaosConfigurationException")
    void shouldRejectPathTraversal(final String path) {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      assertThatThrownBy(() -> chaos.fillDisk(container, path, 10))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("Path traversal not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "500", "5.5M", "500T"})
    @DisplayName("fillDiskBySize — invalid size formats throw ChaosConfigurationException")
    void shouldRejectInvalidSizes(final String size) {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      assertThatThrownBy(() -> chaos.fillDiskBySize(container, "/tmp", size))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("Invalid size format");
    }
  }

  // ==================== Cleanup ====================

  @Nested
  @DisplayName("Cleanup / reset")
  class CleanupTests {

    @Test
    @DisplayName("reset removes chaos-disk-load files from the container")
    void shouldRemoveLoadFiles() throws Exception {
      container = startDebian();
      chaos = new CgroupsDiskChaos();

      // Use fillDiskBySize (fallocate, near-instant) rather than fillDisk(percentage)
      // to avoid multi-GB dd writes on the overlay filesystem.
      chaos.fillDiskBySize(container, "/tmp", "10M");
      chaos.reset(container);

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-load").getExitCode())
          .isNotZero();
    }

    @Test
    @DisplayName("reset stops stress-ng workers")
    void shouldStopStressOnReset() throws Exception {
      container = startDebian();
      chaos = new CgroupsDiskChaos();
      chaos.stressDisk(container, 1);
      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .until(() -> chaos.isStressed(container));

      chaos.reset(container);

      // Use chaos.isStressed() — it applies the zombie-filter (state Z check via /proc/*/stat)
      // so briefly-lingering zombie processes after SIGKILL do not cause a false positive.
      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(15))
          .pollDelay(java.time.Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isFalse());
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> startDebian() {
    final var c = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    c.start();
    return c;
  }

  /**
   * Starts a Debian-based container with a 512 MB tmpfs mounted at {@code /tmp}.
   *
   * <p>Using tmpfs bounds percentage-based disk fills: at 90% of 512 MB the fill writes ≤ 460 MB
   * via {@code dd}, which is acceptable for CI. Without tmpfs the backing overlay filesystem on
   * Docker Desktop is ~60 GB, making even a 10% fill write 6 GB — too slow for tests.
   */
  private static GenericContainer<?> startDebianWithTmpFs() {
    final var c = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
        .withTmpFs(Map.of("/tmp", "rw,size=512m"));
    c.start();
    return c;
  }

  private static GenericContainer<?> startAlpine() {
    final var c = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"));
    c.start();
    return c;
  }

  private static GenericContainer<?> startAlpineWithTmpFs() {
    final var c = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withTmpFs(Map.of("/tmp", "rw,size=512m"));
    c.start();
    return c;
  }
}
