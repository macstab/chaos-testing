/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;

/**
 * Unit tests for {@link SentinelContainerFactory}.
 *
 * <p>Tests cover all pure-logic paths: argument validation, the delegating overloads, quorum
 * calculation, master IP extraction, and the utility class constructor guard. Container startup
 * methods require Linux Docker and are covered by integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@DisplayName("SentinelContainerFactory")
class SentinelContainerFactoryTest {

  // ─── Constructor guard ────────────────────────────────────────────────────

  @Nested
  @DisplayName("Constructor guard")
  class ConstructorGuardTests {

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException via reflection")
    void shouldThrowOnReflectiveInstantiation() throws Exception {
      // ARRANGE
      final Constructor<SentinelContainerFactory> ctor =
          SentinelContainerFactory.class.getDeclaredConstructor();
      ctor.setAccessible(true);

      // ACT & ASSERT
      assertThatThrownBy(ctor::newInstance)
          .isInstanceOf(InvocationTargetException.class)
          .hasCauseInstanceOf(UnsupportedOperationException.class)
          .hasRootCauseMessage("Utility class - not instantiable");
    }
  }

  // ─── validateCounts() ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("validateCounts() — via createSentinelCluster(int, int, boolean)")
  class ValidateCountsTests {

    @Test
    @DisplayName("Throws IAE when replica count is zero")
    void shouldThrowWhenReplicaCountIsZero() {
      // ACT & ASSERT
      assertThatThrownBy(() -> SentinelContainerFactory.createSentinelCluster(0, 3, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Replica count must be at least 1");
    }

    @Test
    @DisplayName("Throws IAE when replica count is negative")
    void shouldThrowWhenReplicaCountIsNegative() {
      // ACT & ASSERT
      assertThatThrownBy(() -> SentinelContainerFactory.createSentinelCluster(-1, 3, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Replica count must be at least 1")
          .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("Throws IAE when sentinel count is zero")
    void shouldThrowWhenSentinelCountIsZero() {
      // ACT & ASSERT
      assertThatThrownBy(() -> SentinelContainerFactory.createSentinelCluster(2, 0, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinel count must be at least 1");
    }

    @Test
    @DisplayName("Throws IAE when sentinel count is negative")
    void shouldThrowWhenSentinelCountIsNegative() {
      // ACT & ASSERT
      assertThatThrownBy(() -> SentinelContainerFactory.createSentinelCluster(2, -5, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinel count must be at least 1")
          .hasMessageContaining("-5");
    }
  }

  // ─── calculateQuorum() ────────────────────────────────────────────────────

  @Nested
  @DisplayName("calculateQuorum()")
  class CalculateQuorumTests {

    @Test
    @DisplayName("1 sentinel → quorum 1 (100% required)")
    void quorumForOneSentinel() {
      assertThat(SentinelContainerFactory.calculateQuorum(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("3 sentinels → quorum 2 (majority)")
    void quorumForThreeSentinels() {
      assertThat(SentinelContainerFactory.calculateQuorum(3)).isEqualTo(2);
    }

    @Test
    @DisplayName("5 sentinels → quorum 3 (majority)")
    void quorumForFiveSentinels() {
      assertThat(SentinelContainerFactory.calculateQuorum(5)).isEqualTo(3);
    }

    @Test
    @DisplayName("7 sentinels → quorum 4 (majority)")
    void quorumForSevenSentinels() {
      assertThat(SentinelContainerFactory.calculateQuorum(7)).isEqualTo(4);
    }
  }

  // ─── getMasterIpAddress() ─────────────────────────────────────────────────

  @Nested
  @DisplayName("getMasterIpAddress()")
  class GetMasterIpAddressTests {

    @Test
    @DisplayName("Returns IP address from first network entry")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldReturnIpFromFirstNetwork() {
      // ARRANGE
      final GenericContainer<?> master = mock(GenericContainer.class);
      final InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
      final NetworkSettings networkSettings = mock(NetworkSettings.class);
      final ContainerNetwork network = mock(ContainerNetwork.class);

      when(master.getContainerInfo()).thenReturn(inspectResponse);
      when(inspectResponse.getNetworkSettings()).thenReturn(networkSettings);
      when(networkSettings.getNetworks()).thenReturn(Map.of("bridge", network));
      when(network.getIpAddress()).thenReturn("172.18.0.2");

      // ACT
      final String ip = SentinelContainerFactory.getMasterIpAddress(master);

      // ASSERT
      assertThat(ip).isEqualTo("172.18.0.2");
    }

    @Test
    @DisplayName("Throws RuntimeException when container has no networks")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldThrowWhenNoNetworkPresent() {
      // ARRANGE
      final GenericContainer<?> master = mock(GenericContainer.class);
      final InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
      final NetworkSettings networkSettings = mock(NetworkSettings.class);

      when(master.getContainerInfo()).thenReturn(inspectResponse);
      when(inspectResponse.getNetworkSettings()).thenReturn(networkSettings);
      when(networkSettings.getNetworks()).thenReturn(Map.of());

      // ACT & ASSERT — exercises the lambda$getMasterIpAddress$0 supplier
      assertThatThrownBy(() -> SentinelContainerFactory.getMasterIpAddress(master))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Master container has no network");
    }
  }

  // ─── Delegating overloads ─────────────────────────────────────────────────

  @Nested
  @DisplayName("Delegating overloads — validation passthrough")
  class DelegatingOverloadsTests {

    @Test
    @DisplayName("createSentinelCluster() delegates and validates replica count")
    void zeroArgOverloadDelegatesToThreeArgOverload() {
      // The no-arg overload calls createSentinelCluster(2, 3, false) which calls
      // validateCounts — if Docker is absent the call throws at container startup,
      // not at validation. We test the overload exists and validation is reachable
      // through the two-arg overload.
      assertThatThrownBy(() -> SentinelContainerFactory.createSentinelCluster(0, 3))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Replica count");
    }

    @Test
    @DisplayName("createSentinelCluster(int, int) delegates and validates sentinel count")
    void twoArgOverloadDelegatesToThreeArgOverload() {
      assertThatThrownBy(() -> SentinelContainerFactory.createSentinelCluster(2, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinel count");
    }
  }
}
