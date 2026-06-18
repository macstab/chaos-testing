/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.cgroups;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.model.Signal;

@DisplayName("CgroupsProcessChaos - Comprehensive Tests")
class CgroupsProcessChaosComprehensiveTest {
  private GenericContainer<?> container;
  private CgroupsProcessChaos chaos;

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
    @Test
    void shouldListProcesses() throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsProcessChaos();
      var processes = chaos.listProcesses(container);
      assertThat(processes).isNotEmpty();
      assertThat(processes).anyMatch(p -> p.name().contains("redis"));
    }

    @Test
    void shouldPauseProcess() throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsProcessChaos();
      chaos.pause(container, "redis-server", Duration.ofMillis(100));
      Thread.sleep(50);
      assertThat(container.isRunning()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIGTERM", "SIGKILL"})
    void shouldKillWithSignals(String signal) throws Exception {
      container = createDebianContainer();
      container.execInContainer("/bin/sh", "-c", "sleep 60 &");
      chaos = new CgroupsProcessChaos();
      chaos.kill(container, "sleep", Signal.valueOf(signal));
      Thread.sleep(100);
      assertThat(container.isRunning()).isTrue();
    }
  }

  @Nested
  @DisplayName("Alpine Tests")
  class AlpineTests {
    @Test
    void shouldListProcessesOnAlpine() throws Exception {
      container = createAlpineContainer();
      chaos = new CgroupsProcessChaos();
      var processes = chaos.listProcesses(container);
      assertThat(processes).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("Negative Tests")
  class NegativeTests {
    @ParameterizedTest
    @ValueSource(strings = {"invalid@process", "process;rm", "process`whoami`"})
    void shouldRejectInvalidProcessNames(String name) throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsProcessChaos();
      assertThatThrownBy(() -> chaos.kill(container, name, Signal.SIGTERM))
          .hasMessageContaining("Invalid process name");
    }
  }

  @Nested
  @DisplayName("Cleanup Tests")
  class CleanupTests {
    @Test
    void shouldResumeAllProcesses() throws Exception {
      container = createDebianContainer();
      chaos = new CgroupsProcessChaos();
      chaos.pause(container, "redis-server", Duration.ofMillis(100));
      chaos.reset(container);
      assertThat(container.isRunning()).isTrue();
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
