/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("CgroupsDiskChaos - Comprehensive Tests")
class CgroupsDiskChaosComprehensiveTest {
  private GenericContainer<?> container;
  private CgroupsDiskChaos chaos;

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      if (chaos != null) chaos.reset(container);
      container.stop();
    }
  }

  @Nested
  @DisplayName("Debian Tests")
  class DebianTests {
    @ParameterizedTest
    @ValueSource(ints = {5, 10, 25, 50, 75, 90})
    void shouldFillDisk(int percentage) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsDiskChaos();
      chaos.fillDisk(container, "/tmp", percentage);
      assertThat(container.execInContainer("ls", "/tmp/chaos-load").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void shouldStressDisk(int workers) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsDiskChaos();
      chaos.stressDisk(container, workers);
      assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("Alpine Tests")
  class AlpineTests {
    @Test
    void shouldFillDiskOnAlpine() throws Exception {
      container = createAlpineContainer();
      chaos = new CgroupsDiskChaos();
      chaos.fillDisk(container, "/tmp", 20);
      assertThat(container.isRunning()).isTrue();
    }
  }

  @Nested
  @DisplayName("Negative Tests")
  class NegativeTests {
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 96, 100, 150})
    void shouldRejectInvalidPercentage(int percentage) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsDiskChaos();
      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", percentage))
          .hasMessageContaining("must be in [1, 95]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/../etc", "../../etc/passwd", "/tmp;rm -rf /"})
    void shouldRejectUnsafePaths(String path) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsDiskChaos();
      assertThatThrownBy(() -> chaos.fillDisk(container, path, 10))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Cleanup Tests")
  class CleanupTests {
    @Test
    void shouldRemoveLoadFiles() throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsDiskChaos();
      chaos.fillDisk(container, "/tmp", 10);
      chaos.reset(container);
      assertThat(container.execInContainer("ls", "/tmp/chaos-load").getExitCode()).isNotZero();
    }
  }

  private GenericContainer<?> createDebianContainer() {
    var c = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    c.start();
    return c;
  }

  private GenericContainer<?> createAlpineContainer() {
    var c = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"));
    c.start();
    return c;
  }
}
