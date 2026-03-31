/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/** Unit tests for {@link RawSentinelCluster} record. */
@DisplayName("RawSentinelCluster Record")
class RawSentinelClusterTest {

  private final Network network = mock(Network.class);
  private final GenericContainer<?> master = mock(GenericContainer.class);
  private final GenericContainer<?> replica1 = mock(GenericContainer.class);
  private final GenericContainer<?> sentinel1 = mock(GenericContainer.class);
  private final GenericContainer<?> sentinel2 = mock(GenericContainer.class);

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should create valid cluster record")
    void shouldCreateValid() {
      // ARRANGE & ACT
      final RawSentinelCluster cluster =
          new RawSentinelCluster(network, master, List.of(replica1), List.of(sentinel1, sentinel2));

      // ASSERT
      assertThat(cluster.network()).isSameAs(network);
      assertThat(cluster.master()).isSameAs(master);
      assertThat(cluster.replicas()).containsExactly(replica1);
      assertThat(cluster.sentinels()).containsExactly(sentinel1, sentinel2);
    }

    @Test
    @DisplayName("Should throw for null network")
    void shouldThrowForNullNetwork() {
      assertThatThrownBy(
              () -> new RawSentinelCluster(null, master, List.of(replica1), List.of(sentinel1)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw for null master")
    void shouldThrowForNullMaster() {
      assertThatThrownBy(
              () -> new RawSentinelCluster(network, null, List.of(replica1), List.of(sentinel1)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw for null replicas")
    void shouldThrowForNullReplicas() {
      assertThatThrownBy(() -> new RawSentinelCluster(network, master, null, List.of(sentinel1)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw for null sentinels")
    void shouldThrowForNullSentinels() {
      assertThatThrownBy(() -> new RawSentinelCluster(network, master, List.of(replica1), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("stop()")
  class StopTests {

    @Test
    @DisplayName("Should call stop on all containers and close network")
    void shouldStopAllContainersAndCloseNetwork() {
      final RawSentinelCluster cluster =
          new RawSentinelCluster(network, master, List.of(replica1), List.of(sentinel1, sentinel2));

      cluster.stop();

      verify(sentinel1).stop();
      verify(sentinel2).stop();
      verify(replica1).stop();
      verify(master).stop();
      verify(network).close();
    }
  }

  @Nested
  @DisplayName("close() - AutoCloseable")
  class CloseTests {

    @Test
    @DisplayName("Should call stop when closed")
    void shouldCallStopWhenClosed() {
      final RawSentinelCluster cluster =
          new RawSentinelCluster(network, master, List.of(replica1), List.of(sentinel1, sentinel2));

      cluster.close();

      verify(sentinel1).stop();
      verify(sentinel2).stop();
      verify(replica1).stop();
      verify(master).stop();
      verify(network).close();
    }
  }

  @Nested
  @DisplayName("firstSentinel()")
  class FirstSentinel {

    @Test
    @DisplayName("Should return first sentinel from list")
    void shouldReturnFirstSentinel() {
      final RawSentinelCluster cluster =
          new RawSentinelCluster(network, master, List.of(replica1), List.of(sentinel1, sentinel2));

      assertThat(cluster.firstSentinel()).isSameAs(sentinel1);
    }

    @Test
    @DisplayName("Should throw for empty sentinel list")
    void shouldThrowForEmptySentinels() {
      final RawSentinelCluster cluster =
          new RawSentinelCluster(network, master, List.of(replica1), List.of());

      assertThatThrownBy(cluster::firstSentinel).isInstanceOf(IndexOutOfBoundsException.class);
    }
  }
}
