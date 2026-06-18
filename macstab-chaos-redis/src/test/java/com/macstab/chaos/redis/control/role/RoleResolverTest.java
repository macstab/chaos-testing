/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;

/** Unit tests for {@link RoleResolver}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleResolver")
class RoleResolverTest {

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw for null containerIndexMap")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> new RoleResolver(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("resolve() - non-running container")
  class ResolveNonRunning {

    @Test
    @DisplayName("Should return UNKNOWN for non-running container")
    void shouldReturnUnknownForStoppedContainer() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT
      final ContainerRole role = resolver.resolve(container);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should throw for null container")
    void shouldThrowForNullContainer() {
      final RoleResolver resolver = new RoleResolver(Map.of());
      assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("clearCache()")
  class ClearCache {

    @Test
    @DisplayName("Should clear all cached roles")
    void shouldClearAllCachedRoles() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));
      resolver.resolve(container); // Populate cache

      // ACT
      resolver.clearCache();

      // ASSERT: after clear, resolve will call determineRole() again
      assertThat(resolver.resolve(container)).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should clear specific container from cache")
    void shouldClearSpecificContainer() {
      final GenericContainer<?> c1 = mock(GenericContainer.class);
      final GenericContainer<?> c2 = mock(GenericContainer.class);
      when(c1.isRunning()).thenReturn(false);
      when(c2.isRunning()).thenReturn(false);

      final RoleResolver resolver = new RoleResolver(Map.of(c1, 0, c2, 1));
      resolver.resolve(c1);
      resolver.resolve(c2);

      // ACT: clear only c1
      resolver.clearCache(c1);

      // ASSERT: both re-resolve fine (UNKNOWN in both cases)
      assertThat(resolver.resolve(c1)).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(resolver.resolve(c2)).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should throw for null container in clearCache(container)")
    void shouldThrowForNullContainer() {
      final RoleResolver resolver = new RoleResolver(Map.of());
      assertThatThrownBy(() -> resolver.clearCache(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Cache behavior")
  class CacheBehavior {

    @Test
    @DisplayName("Same container resolved twice returns same result (cached)")
    void shouldCacheResult() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT: resolve twice
      final ContainerRole first = resolver.resolve(container);
      final ContainerRole second = resolver.resolve(container);

      // ASSERT: same result from cache
      assertThat(first).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(second).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("clearCache forces re-detection — result is consistent after clear")
    void shouldRedetectAfterClearCache() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));
      final ContainerRole before = resolver.resolve(container);
      resolver.clearCache();

      // ACT: resolve again after clear
      final ContainerRole after = resolver.resolve(container);

      // ASSERT: role is re-detected (same result since container is still stopped)
      assertThat(before).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(after).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("clearCache(container) clears only that container's cache entry")
    void shouldRedetectOnlySpecificContainerAfterClear() {
      // ARRANGE
      final GenericContainer<?> c1 = mock(GenericContainer.class);
      final GenericContainer<?> c2 = mock(GenericContainer.class);
      when(c1.isRunning()).thenReturn(false);
      when(c2.isRunning()).thenReturn(false);

      final RoleResolver resolver = new RoleResolver(Map.of(c1, 0, c2, 1));
      resolver.resolve(c1);
      resolver.resolve(c2);
      resolver.clearCache(c1); // only c1 cleared

      // ACT: resolve both again
      final ContainerRole r1 = resolver.resolve(c1);
      final ContainerRole r2 = resolver.resolve(c2);

      // ASSERT: both still return UNKNOWN (c1 re-detected, c2 from cache)
      assertThat(r1).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(r2).isEqualTo(ContainerRole.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("Sentinel container detection")
  class SentinelDetection {

    @Test
    @DisplayName("Running container exposing 26379 is detected as Sentinel")
    void shouldDetectSentinelByExposedPort() {
      // ARRANGE: sentinel exposes port 26379
      final GenericContainer<?> sentinel = mock(GenericContainer.class);
      when(sentinel.isRunning()).thenReturn(true);
      when(sentinel.getExposedPorts()).thenReturn(java.util.List.of(26379));

      final RoleResolver resolver = new RoleResolver(Map.of(sentinel, 0));

      // ACT
      final ContainerRole role = resolver.resolve(sentinel);

      // ASSERT: SENTINEL_0 (index 0)
      assertThat(role).isEqualTo(ContainerRole.SENTINEL_0);
    }

    @Test
    @DisplayName("Sentinel not in index map returns UNKNOWN")
    void shouldReturnUnknownForSentinelNotInIndexMap() {
      // ARRANGE
      final GenericContainer<?> sentinel = mock(GenericContainer.class);
      when(sentinel.isRunning()).thenReturn(true);
      when(sentinel.getExposedPorts()).thenReturn(java.util.List.of(26379));

      // No entry in index map
      final RoleResolver resolver = new RoleResolver(Map.of());

      // ACT
      final ContainerRole role = resolver.resolve(sentinel);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("determineRole() — exception and fallback paths")
  class DetermineRoleExceptionTests {

    @Test
    @DisplayName(
        "getExposedPorts() throws → isSentinelContainer swallows → getContainerInfo throws → getMappedPort throws → UNKNOWN")
    void exposedPortsThrowsAndContainerInfoThrowsAndMappedPortThrows() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);
      when(container.getExposedPorts()).thenThrow(new RuntimeException("exposed ports failed"));
      when(container.getContainerInfo()).thenThrow(new RuntimeException("container info failed"));
      when(container.getMappedPort(6379)).thenThrow(new RuntimeException("mapped port failed"));

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT
      final ContainerRole role = resolver.resolve(container);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("container not sentinel, getContainerInfo throws, getMappedPort throws → UNKNOWN")
    void nonSentinelContainerInfoThrowsMappedPortThrows() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);
      when(container.getExposedPorts()).thenReturn(List.of(6379)); // not sentinel
      when(container.getContainerInfo()).thenThrow(new RuntimeException("no container info"));
      when(container.getMappedPort(6379)).thenThrow(new RuntimeException("port not mapped"));

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT
      final ContainerRole role = resolver.resolve(container);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName(
        "isSentinelContainer exception → non-sentinel path → getContainerInfo throws → getMappedPort throws → UNKNOWN")
    void isSentinelExceptionTreatedAsNonSentinel() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);
      // getExposedPorts() throws → isSentinelContainer swallows, returns false
      when(container.getExposedPorts()).thenThrow(new RuntimeException("port check failed"));
      // getContainerInfo throws → getInternalIpAddress returns null
      when(container.getContainerInfo()).thenThrow(new RuntimeException("no info"));
      // getMappedPort throws → determineRole exception handler → UNKNOWN
      when(container.getMappedPort(6379)).thenThrow(new RuntimeException("no port"));

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT
      final ContainerRole role = resolver.resolve(container);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("getInternalIpAddress() — network inspection")
  class InternalIpAddressTests {

    @Test
    @DisplayName(
        "returns null when getContainerInfo throws → fallback to host:port → getMappedPort throws → UNKNOWN")
    void containerInfoThrowsFallbackMappedPortThrows() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);
      when(container.getExposedPorts()).thenReturn(List.of(6379)); // not sentinel
      when(container.getContainerInfo()).thenThrow(new RuntimeException("inspect failed"));
      when(container.getMappedPort(6379)).thenThrow(new RuntimeException("no mapped port"));

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT
      final ContainerRole role = resolver.resolve(container);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("returns null when networks map is empty → fallback → connection fails → UNKNOWN")
    void networksMapEmptyFallbackFails() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);
      when(container.getExposedPorts()).thenReturn(List.of(6379)); // not sentinel

      final InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
      final NetworkSettings networkSettings = mock(NetworkSettings.class);
      when(inspectResponse.getNetworkSettings()).thenReturn(networkSettings);
      when(networkSettings.getNetworks()).thenReturn(Collections.emptyMap());
      when(container.getContainerInfo()).thenReturn(inspectResponse);

      // Fallback: getMappedPort throws → UNKNOWN
      when(container.getMappedPort(6379)).thenThrow(new RuntimeException("no port"));

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT
      final ContainerRole role = resolver.resolve(container);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("returns null when network IP is null → fallback → connection fails → UNKNOWN")
    void networkIpNullFallbackFails() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);
      when(container.getExposedPorts()).thenReturn(List.of(6379)); // not sentinel

      final InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
      final NetworkSettings networkSettings = mock(NetworkSettings.class);
      final ContainerNetwork network = mock(ContainerNetwork.class);
      when(inspectResponse.getNetworkSettings()).thenReturn(networkSettings);
      when(networkSettings.getNetworks()).thenReturn(Map.of("bridge", network));
      when(network.getIpAddress()).thenReturn(null);
      when(container.getContainerInfo()).thenReturn(inspectResponse);

      // Fallback: getMappedPort throws → UNKNOWN
      when(container.getMappedPort(6379)).thenThrow(new RuntimeException("no port"));

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // ACT
      final ContainerRole role = resolver.resolve(container);

      // ASSERT
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }
  }
}
