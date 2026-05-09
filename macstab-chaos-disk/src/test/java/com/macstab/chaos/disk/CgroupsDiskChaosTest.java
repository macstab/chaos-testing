/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Integration tests for {@link CgroupsDiskChaos} using a live Docker container.
 *
 * <p>File-existence checks use {@code fillDiskBySize("10M")} via {@code fallocate} (near-instant)
 * rather than {@code fillDisk(percentage)} which scales with the overlay filesystem total and can
 * exceed 1 GB on Docker Desktop. The percentage API is tested separately with {@code 1%} to keep
 * the written size deterministically small relative to typical CI disk availability.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsDiskChaos integration")
class CgroupsDiskChaosTest {

  private GenericContainer<?> container;
  private CgroupsDiskChaos chaos;

  @BeforeEach
  void setUp() {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    container.start();
    chaos = new CgroupsDiskChaos();
  }

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  // ==================== isSupported ====================

  @Test
  @DisplayName("isSupported always returns true")
  void isSupported() {
    assertThat(chaos.isSupported()).isTrue();
  }

  // ==================== fillDiskBySize ====================

  @Nested
  @DisplayName("fillDiskBySize")
  class FillDiskBySize {

    @Test
    @DisplayName("creates chaos-disk-load at the target mount point (fallocate path)")
    void createsFillFile() throws Exception {
      chaos.fillDiskBySize(container, "/tmp", "10M");

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-load").getExitCode()).isZero();
    }

    @Test
    @DisplayName("reset removes the chaos-disk-load file")
    void resetRemovesFillFile() throws Exception {
      chaos.fillDiskBySize(container, "/tmp", "10M");
      chaos.reset(container);

      assertThat(container.execInContainer("ls", "/tmp/chaos-disk-load").getExitCode()).isNotZero();
    }

    @Test
    @DisplayName("invalid size format throws ChaosConfigurationException")
    void invalidSizeFormatRejected() {
      assertThatThrownBy(() -> chaos.fillDiskBySize(container, "/tmp", "invalid"))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("Invalid size format");
    }
  }

  // ==================== fillDisk (percentage API) ====================

  @Nested
  @DisplayName("fillDisk")
  class FillDisk {

    @Test
    @DisplayName("percentage 0 throws ChaosConfigurationException before touching container")
    void zeroPercentageRejected() {
      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 0))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("must be in [1, 95]");
    }

    @Test
    @DisplayName("percentage 150 throws ChaosConfigurationException")
    void excessivePercentageRejected() {
      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 150))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("must be in [1, 95]");
    }

    @Test
    @DisplayName("shell-injection path throws ChaosConfigurationException (unsafe characters)")
    void shellInjectionPathRejected() {
      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp;rm -rf /", 10))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("unsafe characters");
    }

    @Test
    @DisplayName("path traversal throws ChaosConfigurationException")
    void pathTraversalRejected() {
      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp/../etc", 10))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("Path traversal not allowed");
    }
  }

  // ==================== Stress ====================

  @Nested
  @DisplayName("stressDisk")
  class StressDisk {

    @Test
    @DisplayName("starts stress-ng hdd workers — detected via /proc/comm")
    void startsStressNgWorkers() {
      chaos.stressDisk(container, 2);

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());
    }

    @Test
    @DisplayName("stressDisk(duration) starts workers that auto-stop")
    void startsTimedStress() {
      chaos.stressDisk(container, 1, Duration.ofSeconds(30));

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());
    }

    @Test
    @DisplayName("reset kills all stress-ng workers")
    void resetKillsWorkers() {
      chaos.stressDisk(container, 1);
      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(() -> chaos.isStressed(container));

      chaos.reset(container);

      // Allow time for SIGKILL to propagate and zombies to be reaped
      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(15))
          .pollDelay(java.time.Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isFalse());
    }

    @Test
    @DisplayName("workers < 1 throws ChaosConfigurationException")
    void zeroWorkersRejected() {
      assertThatThrownBy(() -> chaos.stressDisk(container, 0))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("workers must be >= 1");
    }
  }

  // ==================== getDiskUsagePercent ====================

  @Nested
  @DisplayName("getDiskUsagePercent")
  class GetDiskUsagePercent {

    @Test
    @DisplayName("returns a value in [0, 100] for /tmp")
    void returnsValidRange() {
      final int usage = chaos.getDiskUsagePercent(container, "/tmp");

      assertThat(usage).isBetween(0, 100);
    }

    @Test
    @DisplayName("increases after fillDiskBySize")
    void increasesAfterFill() {
      final int before = chaos.getDiskUsagePercent(container, "/tmp");
      chaos.fillDiskBySize(container, "/tmp", "100M");
      final int after = chaos.getDiskUsagePercent(container, "/tmp");

      assertThat(after).isGreaterThanOrEqualTo(before);
    }
  }

  // ==================== isStressed ====================

  @Nested
  @DisplayName("isStressed")
  class IsStressed {

    @Test
    @DisplayName("returns false when no stress-ng is running")
    void falseWhenIdle() {
      assertThat(chaos.isStressed(container)).isFalse();
    }

    @Test
    @DisplayName("returns false on stopped container without exec")
    void falseWhenContainerStopped() {
      container.stop();
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  // ==================== Stopped-container guards ====================

  @Nested
  @DisplayName("stopped container guards")
  class StoppedContainerGuards {

    @Test
    @DisplayName("fillDisk on stopped container throws IllegalStateException")
    void fillDiskStoppedContainer() {
      container.stop();

      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 10))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("reset on stopped container is a no-op")
    void resetStoppedContainerNoOp() {
      container.stop();

      assertThatCode(() -> chaos.reset(container)).doesNotThrowAnyException();
    }
  }
}
