/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.cgroups;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("CgroupsMemoryChaos - Comprehensive Tests")
class CgroupsMemoryChaosComprehensiveTest {
  private GenericContainer<?> container;
  private CgroupsMemoryChaos chaos;

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
    @ValueSource(strings = {"10M", "50M", "100M", "256M", "512M"})
    void shouldStress(String size) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsMemoryChaos();
      chaos.stress(container, size);
      assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"5M", "10M", "25M"})
    void shouldSetPressure(String threshold) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsMemoryChaos();
      chaos.setPressure(container, threshold);
      assertThat(container.isRunning()).isTrue();
    }
  }

  @Nested
  @DisplayName("Alpine Tests")
  class AlpineTests {
    @Test
    void shouldStressOnAlpine() throws Exception {
      container = createAlpineContainer();
      chaos = new CgroupsMemoryChaos();
      chaos.stress(container, "50M");
      assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("Negative Tests")
  class NegativeTests {
    @ParameterizedTest
    @ValueSource(strings = {"invalid", "10", "10Z", "ABC"})
    void shouldRejectInvalidSize(String size) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsMemoryChaos();
      assertThatThrownBy(() -> chaos.stress(container, size))
          .hasMessageContaining("Invalid size format");
    }

    @Test
    void shouldRejectTooLarge() throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsMemoryChaos();
      assertThatThrownBy(() -> chaos.stress(container, "200000G"))
          .hasMessageContaining("too large");
    }
  }

  @Nested
  @DisplayName("Cleanup Tests")
  class CleanupTests {
    @Test
    void shouldKillStressProcesses() throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsMemoryChaos();
      chaos.stress(container, "100M");
      chaos.reset(container);
      assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isNotZero();
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
