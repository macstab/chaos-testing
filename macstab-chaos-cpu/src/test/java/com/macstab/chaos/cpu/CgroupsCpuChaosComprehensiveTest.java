/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("CgroupsCpuChaos - Comprehensive Tests")
class CgroupsCpuChaosComprehensiveTest {
  private GenericContainer<?> container;
  private CgroupsCpuChaos chaos;

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
    @ValueSource(ints = {10, 25, 50, 75, 100})
    void shouldThrottle(int percentage) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsCpuChaos();
      chaos.throttle(container, percentage);
      assertThat(container.execInContainer("pgrep", "cpulimit").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void shouldStress(int workers) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsCpuChaos();
      chaos.stress(container, workers);
      assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("Alpine Tests")
  class AlpineTests {
    @Test
    void shouldStressWithTimeout() throws Exception {
      container = createAlpineContainer();
      chaos = new CgroupsCpuChaos();
      chaos.stress(container, 2, Duration.ofSeconds(5));
      assertThat(container.execInContainer("pgrep", "stress-ng").getExitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("Negative Tests")
  class NegativeTests {
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, 200})
    void shouldRejectInvalidPercentage(int percentage) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsCpuChaos();
      assertThatThrownBy(() -> chaos.throttle(container, percentage))
          .hasMessageContaining("must be in [1, 100]");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void shouldRejectInvalidWorkers(int workers) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsCpuChaos();
      assertThatThrownBy(() -> chaos.stress(container, workers))
          .hasMessageContaining("must be >= 1");
    }
  }

  @Nested
  @DisplayName("Cleanup Tests")
  class CleanupTests {
    @Test
    void shouldKillAllProcesses() throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsCpuChaos();
      chaos.stress(container, 2);
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
