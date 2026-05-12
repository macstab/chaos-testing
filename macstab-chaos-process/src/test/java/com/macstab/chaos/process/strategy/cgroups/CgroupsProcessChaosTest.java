/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.cgroups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.model.Signal;

/**
 * Integration tests for {@link CgroupsProcessChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class CgroupsProcessChaosTest {

  private GenericContainer<?> container;
  private CgroupsProcessChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    container.start();

    chaos = new CgroupsProcessChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should list processes")
  void shouldListProcesses() throws Exception {
    final var processes = chaos.listProcesses(container);

    assertThat(processes).isNotEmpty();
    assertThat(processes).anyMatch(p -> p.name().contains("redis"));
  }

  @Test
  @DisplayName("should pause process")
  void shouldPauseProcess() throws Exception {
    final String processName = "redis-server";
    final Duration duration = Duration.ofMillis(100);

    chaos.pause(container, processName, duration);

    // Process should be paused briefly
    Thread.sleep(50);
    assertThat(chaos.listProcesses(container)).isNotEmpty();
  }

  @Test
  @DisplayName("should kill process with signal")
  void shouldKillProcess() throws Exception {
    // setsid detaches sleep from the exec session so the exec-session SIGKILL
    // on shell exit does not kill it before we get to signal it ourselves.
    chaos.installTools(container);
    container.execInContainer("/bin/sh", "-c", "setsid sleep 60 </dev/null >/dev/null 2>&1 &");
    Thread.sleep(100);

    chaos.kill(container, "sleep", Signal.SIGTERM);

    Thread.sleep(200);
    // redis is PID 1 and does not reap children, so a killed sleep lingers as a
    // zombie ("Z" state) that pgrep still finds. Assert no *non-zombie* sleep
    // remains — that's what "the process was killed" actually means here.
    final var result =
        container.execInContainer(
            "/bin/sh", "-c", "ps -eo stat,comm | awk '$2==\"sleep\" && $1 !~ /^Z/'");
    assertThat(result.getStdout()).isEmpty();
  }

  @Test
  @DisplayName("should reset process chaos")
  void shouldReset() throws Exception {
    chaos.reset(container);

    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid process name")
  void shouldRejectInvalidProcessName() throws Exception {
    assertThatThrownBy(() -> chaos.kill(container, "invalid@process!", Signal.SIGTERM))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid process name");
  }

  @Test
  @DisplayName("should reject invalid duration")
  void shouldRejectInvalidDuration() throws Exception {
    assertThatThrownBy(() -> chaos.pause(container, "redis-server", Duration.ofMillis(-100)))
        .hasMessageContaining("must be positive");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.listProcesses(container))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not running");
  }
}
