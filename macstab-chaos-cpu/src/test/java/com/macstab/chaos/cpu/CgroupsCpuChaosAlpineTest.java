/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;

/**
 * Alpine-specific integration tests for {@link CgroupsCpuChaos} (redis:7.4-alpine).
 *
 * <p>Validates that package installation and all stress operations work on Alpine's musl-based
 * images with apk package manager.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsCpuChaos (Alpine)")
class CgroupsCpuChaosAlpineTest {

  private GenericContainer<?> container;
  private CgroupsCpuChaos chaos;

  @BeforeEach
  void setUp() {
    container =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withCreateContainerCmdModifier(cmd -> {
                cmd.withCapAdd(Capability.SYS_NICE);
                cmd.getHostConfig().withInit(Boolean.TRUE);  // tini as PID 1 — reaps orphaned stress-ng zombies
            });
    container.start();
    chaos = new CgroupsCpuChaos();
  }

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Nested
  @DisplayName("core operations")
  class CoreOperations {

    @Test
    @DisplayName("stress starts workers")
    void stressStartsWorkers() {
      chaos.stress(container, 2);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("stress with timeout starts workers")
    void stressWithTimeoutStartsWorkers() {
      chaos.stress(container, 1, Duration.ofSeconds(5));
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("throttle starts cpulimit")
    void throttleStartsCpulimit() {
      chaos.throttle(container, 50);
      assertThat(chaos.isThrottled(container)).isTrue();
    }

    @Test
    @DisplayName("getAvailableCores returns >= 1")
    void coreCountPositive() {
      assertThat(chaos.getAvailableCores(container)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("getCurrentUsage > 0 after stress")
    void usageDetectsStress() {
      chaos.stress(container, 2);
      await()
          .atMost(10, TimeUnit.SECONDS)
          .pollInterval(1, TimeUnit.SECONDS)
          .until(() -> chaos.getCurrentUsage(container) > 0);
    }
  }

  @Nested
  @DisplayName("state transitions")
  class StateTransitions {

    @Test
    @DisplayName("isThrottled + isStressed transition: false -> true -> reset -> false")
    void fullTransition() {
      // GIVEN — clean state
      assertThat(chaos.isThrottled(container)).isFalse();
      assertThat(chaos.isStressed(container)).isFalse();

      // WHEN — inject
      chaos.stress(container, 1);
      chaos.throttle(container, 25);

      // THEN — both active
      assertThat(chaos.isStressed(container)).isTrue();
      assertThat(chaos.isThrottled(container)).isTrue();

      // WHEN — reset
      chaos.reset(container);

      // THEN — both gone
      assertThat(chaos.isStressed(container)).isFalse();
      assertThat(chaos.isThrottled(container)).isFalse();
    }

    @Test
    @DisplayName("stressWithThrottle starts both processes")
    void stressWithThrottleStartsBoth() {
      chaos.stressWithThrottle(container, 2, 50);

      assertThat(chaos.isStressed(container)).isTrue();
      assertThat(chaos.isThrottled(container)).isTrue();
    }
  }
}
