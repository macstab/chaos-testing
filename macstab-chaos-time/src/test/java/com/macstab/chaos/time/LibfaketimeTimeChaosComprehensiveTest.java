/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("LibfaketimeTimeChaos - Comprehensive Tests")
class LibfaketimeTimeChaosComprehensiveTest {
  private GenericContainer<?> container;
  private LibfaketimeTimeChaos chaos;

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
    @ValueSource(ints = {1, 5, 10, 24, 72})
    void shouldShiftHours(int hours) throws Exception {
      container = createDebianContainer();
      chaos = new LibfaketimeTimeChaos();
      chaos.shift(container, Duration.ofHours(hours));
      assertThat(container.execInContainer("cat", "/tmp/faketime").getStdout().trim())
          .contains("h");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 1.0, 2.0, 10.0})
    void shouldSetDrift(double speed) throws Exception {
      container = createDebianContainer();
      chaos = new LibfaketimeTimeChaos();
      chaos.drift(container, speed);
      assertThat(container.execInContainer("cat", "/tmp/faketime").getStdout().trim())
          .startsWith("x");
    }
  }

  @Nested
  @DisplayName("Alpine Tests")
  class AlpineTests {
    @Test
    void shouldShiftBackward() throws Exception {
      container = createAlpineContainer();
      chaos = new LibfaketimeTimeChaos();
      chaos.shift(container, Duration.ofDays(-1));
      assertThat(container.execInContainer("cat", "/tmp/faketime").getStdout().trim())
          .isEqualTo("-1d");
    }
  }

  @Nested
  @DisplayName("Negative Tests")
  class NegativeTests {
    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -0.1, 0.0})
    void shouldRejectInvalidSpeed(double speed) throws Exception {
      container = createDebianContainer();
      chaos = new LibfaketimeTimeChaos();
      assertThatThrownBy(() -> chaos.drift(container, speed)).hasMessageContaining("must be > 0.0");
    }
  }

  @Nested
  @DisplayName("Cleanup Tests")
  class CleanupTests {
    @Test
    void shouldRemoveTimestampFile() throws Exception {
      container = createDebianContainer();
      chaos = new LibfaketimeTimeChaos();
      chaos.shift(container, Duration.ofHours(5));
      chaos.reset(container);
      assertThat(container.execInContainer("ls", "/tmp/faketime").getExitCode()).isNotZero();
    }
  }

  private GenericContainer<?> createDebianContainer() {
    var c = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    LibfaketimeTimeChaos.enableDynamicTime(c);
    c.start();
    return c;
  }

  private GenericContainer<?> createAlpineContainer() {
    var c = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"));
    LibfaketimeTimeChaos.enableDynamicTime(c);
    c.start();
    return c;
  }
}
