/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.StandaloneRedis;
import com.macstab.chaos.redis.control.lifecycle.TestcontainerController;
import com.macstab.chaos.redis.extension.RedisContainerExtension;

/**
 * Integration tests targeting uncovered code paths in standalone Redis infrastructure.
 *
 * <p>Hits: TestcontainerController.kill() + restart paths, waitForReady with custom timeout, and
 * getContainerInstance() access.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisStandalone(id = "cover", version = "7.4")
@RedisStandalone(id = "killable", version = "7.4")
@DisplayName("Standalone Coverage Integration")
class StandaloneCoverageTest {

  @Test
  @DisplayName("Should expose running container via getContainerInstance")
  void shouldExposeContainerInstance(final List<StandaloneRedis> allRedis) {
    final GenericContainer<?> container = RedisContainerExtension.getContainerInstance("cover");

    assertThat(container).isNotNull();
    assertThat(container.isRunning()).isTrue();
  }

  @Test
  @DisplayName("waitForReady with custom timeout should not throw for running container")
  void shouldWaitForReadyWithCustomTimeout(final List<StandaloneRedis> allRedis) {
    final GenericContainer<?> container = RedisContainerExtension.getContainerInstance("cover");
    final TestcontainerController controller = new TestcontainerController();

    assertThatCode(() -> controller.waitForReady(container, Duration.ofSeconds(5)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should kill dedicated killable container successfully")
  void shouldKillContainer(final List<StandaloneRedis> allRedis) {
    // Use a dedicated "killable" container isolated from other tests
    final GenericContainer<?> container = RedisContainerExtension.getContainerInstance("killable");
    final TestcontainerController controller = new TestcontainerController();

    // Kill via SIGKILL — should not throw
    assertThatCode(() -> controller.kill(container)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("StandaloneRedis list should contain all instances")
  void shouldExposeAllInstances(final List<StandaloneRedis> allRedis) {
    assertThat(allRedis).hasSize(2);
    assertThat(allRedis.get(0).host()).isNotBlank();
    assertThat(allRedis.get(0).port()).isGreaterThan(0);
  }
}
