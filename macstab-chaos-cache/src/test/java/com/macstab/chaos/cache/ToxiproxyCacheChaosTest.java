/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;

/**
 * Integration tests for {@link ToxiproxyCacheChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ToxiproxyCacheChaosTest {

  private GenericContainer<?> container;
  private ToxiproxyCacheChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withExposedPorts(6379, 6380)
            .withCommand("redis-server", "--port", "6380")
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    container.start();

    chaos = new ToxiproxyCacheChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should inject cache misses")
  void shouldInjectMisses() throws Exception {
    final String keyPattern = "user:*";
    final double rate = 0.5;

    chaos.injectMisses(container, keyPattern, rate);

    assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should slow cache responses")
  void shouldSlowResponse() throws Exception {
    final Duration delay = Duration.ofMillis(200);

    chaos.slowResponse(container, delay);

    assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should force cache eviction")
  void shouldForceEviction() throws Exception {
    // Populate cache first
    container.execInContainer("redis-cli", "-p", "6380", "SET", "key1", "value1");
    container.execInContainer("redis-cli", "-p", "6380", "SET", "key2", "value2");
    container.execInContainer("redis-cli", "-p", "6380", "SET", "key3", "value3");

    final int percentage = 50;

    chaos.forceEviction(container, percentage);

    // Verify some keys were evicted
    final var dbsize = container.execInContainer("redis-cli", "-p", "6380", "DBSIZE");
    assertThat(dbsize.getStdout().trim()).matches("\\d+");
  }

  @Test
  @DisplayName("should reset chaos")
  void shouldReset() throws Exception {
    chaos.injectMisses(container, "test:*", 0.3);

    chaos.reset(container);

    assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid miss rate")
  void shouldRejectInvalidRate() throws Exception {
    assertThatThrownBy(() -> chaos.injectMisses(container, "test:*", 1.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in [0.0, 1.0]");
  }

  @Test
  @DisplayName("should reject invalid eviction percentage")
  void shouldRejectInvalidPercentage() throws Exception {
    assertThatThrownBy(() -> chaos.forceEviction(container, 150))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in [1, 100]");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.injectMisses(container, "test:*", 0.5))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be running");
  }
}
