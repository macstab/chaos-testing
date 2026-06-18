/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.failover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.control.lifecycle.ContainerController;
import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.control.role.RoleResolver;
import com.macstab.chaos.redis.exception.ClusterTopologyException;
import com.macstab.chaos.redis.exception.FailoverException;

/**
 * Unit tests for {@link FailoverHelper}.
 *
 * <p>All container and infrastructure dependencies are mocked — no Docker required.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FailoverHelper")
class FailoverHelperTest {

  @Mock private ContainerController controller;

  @Mock private RoleResolver roleResolver;

  // Containers are not final and not sealed — mockable with Mockito
  @SuppressWarnings("rawtypes")
  private GenericContainer masterContainer;

  @SuppressWarnings("rawtypes")
  private GenericContainer replicaContainer;

  @SuppressWarnings("rawtypes")
  private GenericContainer sentinelContainer;

  private FailoverHelper helper;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    masterContainer = mock(GenericContainer.class);
    replicaContainer = mock(GenericContainer.class);
    sentinelContainer = mock(GenericContainer.class);

    // Sensible defaults — all containers running
    when(masterContainer.isRunning()).thenReturn(true);
    when(replicaContainer.isRunning()).thenReturn(true);
    when(sentinelContainer.isRunning()).thenReturn(true);

    // Stub container IDs to avoid NPE in ContainerIdFormatter
    when(masterContainer.getContainerId()).thenReturn("aabbccddeeff001122334455");
    when(replicaContainer.getContainerId()).thenReturn("aabbccddeeff001122334466");
    when(sentinelContainer.getContainerId()).thenReturn("aabbccddeeff001122334477");

    helper =
        new FailoverHelper(
            controller,
            roleResolver,
            List.of(masterContainer, replicaContainer, sentinelContainer));
  }

  // ==================== Constructor ====================

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("Should throw NPE for null controller")
    void shouldThrowOnNullController() {
      assertThatNullPointerException()
          .isThrownBy(() -> new FailoverHelper(null, roleResolver, List.of()))
          .withMessage("controller");
    }

    @Test
    @DisplayName("Should throw NPE for null roleResolver")
    void shouldThrowOnNullRoleResolver() {
      assertThatNullPointerException()
          .isThrownBy(() -> new FailoverHelper(controller, null, List.of()))
          .withMessage("roleResolver");
    }

    @Test
    @DisplayName("Should throw NPE for null allContainers")
    void shouldThrowOnNullContainerList() {
      assertThatNullPointerException()
          .isThrownBy(() -> new FailoverHelper(controller, roleResolver, null))
          .withMessage("allContainers");
    }
  }

  // ==================== findMaster() ====================

  @Nested
  @DisplayName("findMaster()")
  class FindMasterTests {

    @Test
    @DisplayName("Should return running master container")
    @SuppressWarnings("unchecked")
    void shouldReturnRunningMaster() {
      // Arrange
      when(roleResolver.resolve(masterContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act
      final GenericContainer<?> result = helper.findMaster();

      // Assert
      assertThat(result).isSameAs(masterContainer);
    }

    @Test
    @DisplayName("Should skip stopped containers when searching for master")
    @SuppressWarnings("unchecked")
    void shouldSkipStoppedContainers() {
      // Arrange — replica is now master after failover; old master stopped
      when(masterContainer.isRunning()).thenReturn(false);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act
      final GenericContainer<?> result = helper.findMaster();

      // Assert
      assertThat(result).isSameAs(replicaContainer);
      verify(roleResolver, never()).resolve(masterContainer); // stopped — never queried
    }

    @Test
    @DisplayName("Should throw ClusterTopologyException if no master found")
    @SuppressWarnings("unchecked")
    void shouldThrowWhenNoMasterFound() {
      // Arrange — all running but none is master
      when(roleResolver.resolve(masterContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.REPLICA_1);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act & Assert
      assertThatExceptionOfType(ClusterTopologyException.class)
          .isThrownBy(() -> helper.findMaster())
          .withMessageContaining("No master container found");
    }

    @Test
    @DisplayName("Should throw ClusterTopologyException if all containers stopped")
    void shouldThrowWhenAllContainersStopped() {
      // Arrange
      when(masterContainer.isRunning()).thenReturn(false);
      when(replicaContainer.isRunning()).thenReturn(false);
      when(sentinelContainer.isRunning()).thenReturn(false);

      // Act & Assert
      assertThatExceptionOfType(ClusterTopologyException.class)
          .isThrownBy(() -> helper.findMaster())
          .withMessageContaining("No master container found");
    }
  }

  // ==================== findReplicas() ====================

  @Nested
  @DisplayName("findReplicas()")
  class FindReplicasTests {

    @Test
    @DisplayName("Should return all running replica containers")
    @SuppressWarnings("unchecked")
    void shouldReturnRunningReplicas() {
      // Arrange
      when(roleResolver.resolve(masterContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act
      final List<GenericContainer<?>> replicas = helper.findReplicas();

      // Assert
      assertThat(replicas).containsExactly(replicaContainer);
    }

    @Test
    @DisplayName("Should return empty list when no replicas are running")
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyListWhenNoReplicas() {
      // Arrange — only master and sentinel, no replica
      when(roleResolver.resolve(masterContainer)).thenReturn(ContainerRole.MASTER);
      when(replicaContainer.isRunning()).thenReturn(false);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act
      final List<GenericContainer<?>> replicas = helper.findReplicas();

      // Assert
      assertThat(replicas).isEmpty();
    }

    @Test
    @DisplayName("Should return multiple replicas when multiple containers report replica role")
    @SuppressWarnings("unchecked")
    void shouldReturnMultipleReplicas() {
      // Arrange — sentinel is also a replica in this scenario (unlikely, but tests filter logic)
      when(roleResolver.resolve(masterContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.REPLICA_1);

      // Act
      final List<GenericContainer<?>> replicas = helper.findReplicas();

      // Assert
      assertThat(replicas).containsExactlyInAnyOrder(replicaContainer, sentinelContainer);
    }
  }

  // ==================== isNewMasterElected() ====================

  @Nested
  @DisplayName("isNewMasterElected()")
  class IsNewMasterElectedTests {

    @Test
    @DisplayName("Should return true when new master is a different container")
    @SuppressWarnings("unchecked")
    void shouldReturnTrueWhenDifferentMaster() {
      // Arrange — replicaContainer has been promoted
      when(roleResolver.resolve(masterContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act
      final boolean result = helper.isNewMasterElected(masterContainer);

      // Assert
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return false when same container is still master")
    @SuppressWarnings("unchecked")
    void shouldReturnFalseWhenSameMaster() {
      // Arrange
      when(roleResolver.resolve(masterContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act
      final boolean result = helper.isNewMasterElected(masterContainer);

      // Assert
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return false when ClusterTopologyException during polling")
    @SuppressWarnings("unchecked")
    void shouldReturnFalseOnTopologyException() {
      // Arrange — no master found yet (normal during election window)
      when(roleResolver.resolve(any())).thenReturn(ContainerRole.REPLICA_0);

      // Act
      final boolean result = helper.isNewMasterElected(masterContainer);

      // Assert
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should throw NPE for null oldMasterContainer")
    void shouldThrowOnNullMasterContainer() {
      assertThatNullPointerException()
          .isThrownBy(() -> helper.isNewMasterElected(null))
          .withMessage("oldMasterContainer");
    }
  }

  // ==================== triggerFailover() ====================

  @Nested
  @DisplayName("triggerFailover()")
  class TriggerFailoverTests {

    @Test
    @DisplayName("Should kill master and return elapsed duration (default timeout)")
    @SuppressWarnings("unchecked")
    void shouldTriggerFailoverWithDefaultTimeout() {
      // Arrange — replicaContainer becomes master immediately after kill
      doNothing().when(controller).kill(masterContainer);

      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);
      when(masterContainer.isRunning()).thenReturn(false); // stopped after kill

      // Act
      final Duration elapsed = helper.triggerFailover(masterContainer);

      // Assert
      assertThat(elapsed).isNotNull();
      assertThat(elapsed.toMillis()).isGreaterThanOrEqualTo(0);
      verify(controller).kill(masterContainer);
      verify(roleResolver, atLeastOnce()).clearCache();
    }

    @Test
    @DisplayName("Should kill master and return elapsed duration (custom timeout)")
    @SuppressWarnings("unchecked")
    void shouldTriggerFailoverWithCustomTimeout() {
      // Arrange
      doNothing().when(controller).kill(masterContainer);

      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.MASTER);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);
      when(masterContainer.isRunning()).thenReturn(false);

      // Act
      final Duration elapsed = helper.triggerFailover(masterContainer, Duration.ofSeconds(10));

      // Assert
      assertThat(elapsed).isNotNull().isLessThan(Duration.ofSeconds(10));
      verify(controller).kill(masterContainer);
    }

    @Test
    @DisplayName("Should throw NPE for null masterContainer")
    void shouldThrowOnNullMasterContainer() {
      assertThatNullPointerException()
          .isThrownBy(() -> helper.triggerFailover(null))
          .withMessage("masterContainer");
    }

    @Test
    @DisplayName("Should throw NPE for null timeout")
    void shouldThrowOnNullTimeout() {
      assertThatNullPointerException()
          .isThrownBy(() -> helper.triggerFailover(masterContainer, null))
          .withMessage("timeout");
    }

    @Test
    @DisplayName("Should throw FailoverException when timeout expires before new master elected")
    @SuppressWarnings("unchecked")
    void shouldThrowFailoverExceptionOnTimeout() {
      // Arrange — no new master ever elected
      doNothing().when(controller).kill(masterContainer);
      when(masterContainer.isRunning()).thenReturn(false);

      // All remaining running containers report replica — no master available
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Act & Assert — use very short timeout to keep test fast
      assertThatExceptionOfType(FailoverException.class)
          .isThrownBy(() -> helper.triggerFailover(masterContainer, Duration.ofMillis(300)))
          .withMessageContaining("Failover did not complete");
    }

    @Test
    @DisplayName("Should retry multiple times before succeeding")
    @SuppressWarnings("unchecked")
    void shouldRetryUntilNewMasterElected() {
      // Arrange — new master only visible after 3 cache clears
      doNothing().when(controller).kill(masterContainer);
      when(masterContainer.isRunning()).thenReturn(false);

      final AtomicInteger clearCount = new AtomicInteger(0);

      // Simulates Sentinel election delay: replica not yet master on first 2 clears
      when(roleResolver.resolve(replicaContainer))
          .thenAnswer(
              inv -> {
                final int count = clearCount.get();
                return count >= 2 ? ContainerRole.MASTER : ContainerRole.REPLICA_0;
              });
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      // Track cache clears by counting them
      org.mockito.Mockito.doAnswer(
              inv -> {
                clearCount.incrementAndGet();
                return null;
              })
          .when(roleResolver)
          .clearCache();

      // Act
      final Duration elapsed = helper.triggerFailover(masterContainer, Duration.ofSeconds(5));

      // Assert
      assertThat(elapsed).isNotNull();
      verify(roleResolver, atLeastOnce()).clearCache();
    }
  }

  // ==================== Interrupt handling ====================

  @Nested
  @DisplayName("Interrupt handling")
  class InterruptHandlingTests {

    @Test
    @DisplayName("Should throw FailoverException and set interrupt flag when interrupted")
    @SuppressWarnings("unchecked")
    void shouldHandleInterruptDuringWait() throws InterruptedException {
      // Arrange — no new master ever elected, so we'll sit in the wait loop
      doNothing().when(controller).kill(masterContainer);
      when(masterContainer.isRunning()).thenReturn(false);
      when(roleResolver.resolve(replicaContainer)).thenReturn(ContainerRole.REPLICA_0);
      when(roleResolver.resolve(sentinelContainer)).thenReturn(ContainerRole.SENTINEL_0);

      final FailoverException[] caught = new FailoverException[1];
      final boolean[] threadInterrupted = new boolean[1];

      final Thread worker =
          new Thread(
              () -> {
                try {
                  helper.triggerFailover(masterContainer, Duration.ofSeconds(30));
                } catch (final FailoverException ex) {
                  caught[0] = ex;
                  threadInterrupted[0] = Thread.currentThread().isInterrupted();
                }
              });

      // Act — start worker and interrupt it quickly
      worker.start();
      Thread.sleep(80); // let it enter the wait loop
      worker.interrupt();
      worker.join(2_000);

      // Assert
      assertThat(caught[0]).isNotNull().hasMessageContaining("Interrupted");
      assertThat(threadInterrupted[0]).isTrue();
    }
  }
}
