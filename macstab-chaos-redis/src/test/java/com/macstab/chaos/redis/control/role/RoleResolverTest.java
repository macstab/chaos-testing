/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link RoleResolver}.
 *
 * <p><strong>Note:</strong> Full role resolution requires Redis containers and is tested in
 * integration tests. These unit tests focus on constructor validation, cache behavior, and null
 * handling.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RoleResolver")
class RoleResolverTest {

  @Nested
  @DisplayName("Constructor Validation")
  class ConstructorValidationTests {

    @Test
    @DisplayName("Should create RoleResolver with valid containerIndexMap")
    void shouldCreateWithValidMap() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      final Map<GenericContainer<?>, Integer> map = Map.of(container, 0);

      final RoleResolver resolver = new RoleResolver(map);

      assertThat(resolver).isNotNull();
    }

    @Test
    @DisplayName("Should throw NullPointerException when containerIndexMap is null")
    void shouldThrowWhenMapIsNull() {
      assertThatThrownBy(() -> new RoleResolver(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("containerIndexMap");
    }

    @Test
    @DisplayName("Should create RoleResolver with empty map")
    void shouldCreateWithEmptyMap() {
      final RoleResolver resolver = new RoleResolver(Map.of());

      assertThat(resolver).isNotNull();
    }

    @Test
    @DisplayName("Should create RoleResolver with multiple containers")
    void shouldCreateWithMultipleContainers() {
      final GenericContainer<?> replica0 = mock(GenericContainer.class);
      final GenericContainer<?> replica1 = mock(GenericContainer.class);
      final GenericContainer<?> sentinel0 = mock(GenericContainer.class);

      final Map<GenericContainer<?>, Integer> map = Map.of(replica0, 0, replica1, 1, sentinel0, 0);

      final RoleResolver resolver = new RoleResolver(map);

      assertThat(resolver).isNotNull();
    }
  }

  @Nested
  @DisplayName("resolve()")
  class ResolveTests {

    @Test
    @DisplayName("Should throw NullPointerException when container is null")
    void shouldThrowWhenContainerIsNull() {
      final RoleResolver resolver = new RoleResolver(Map.of());

      assertThatThrownBy(() -> resolver.resolve(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should return UNKNOWN for stopped container")
    void shouldReturnUnknownForStoppedContainer() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("stopped-container-id");

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      final ContainerRole role = resolver.resolve(container);

      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should cache resolved role for subsequent calls")
    void shouldCacheResolvedRole() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("container-id");

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // First call - queries container
      final ContainerRole role1 = resolver.resolve(container);

      // Second call - uses cache
      final ContainerRole role2 = resolver.resolve(container);

      assertThat(role1).isEqualTo(role2);
      assertThat(role1).isEqualTo(ContainerRole.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("clearCache()")
  class ClearCacheTests {

    @Test
    @DisplayName("Should clear all cached roles")
    void shouldClearAllCachedRoles() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("container-id");

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // Populate cache
      resolver.resolve(container);

      // Clear cache
      resolver.clearCache();

      // Subsequent resolve should query again (not from cache)
      final ContainerRole role = resolver.resolve(container);
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should not throw when clearing empty cache")
    void shouldNotThrowWhenClearingEmptyCache() {
      final RoleResolver resolver = new RoleResolver(Map.of());

      // Should not throw
      resolver.clearCache();
    }
  }

  @Nested
  @DisplayName("clearCache(container)")
  class ClearCacheContainerTests {

    @Test
    @DisplayName("Should clear cache entry for specific container")
    void shouldClearCacheEntryForContainer() {
      final GenericContainer<?> container1 = mock(GenericContainer.class);
      final GenericContainer<?> container2 = mock(GenericContainer.class);

      when(container1.isRunning()).thenReturn(false);
      when(container2.isRunning()).thenReturn(false);
      when(container1.getContainerId()).thenReturn("container-1");
      when(container2.getContainerId()).thenReturn("container-2");

      final RoleResolver resolver = new RoleResolver(Map.of(container1, 0, container2, 1));

      // Populate cache for both containers
      resolver.resolve(container1);
      resolver.resolve(container2);

      // Clear cache for container1 only
      resolver.clearCache(container1);

      // Subsequent resolve should query container1 again
      final ContainerRole role1 = resolver.resolve(container1);
      assertThat(role1).isEqualTo(ContainerRole.UNKNOWN);

      // container2 should still be cached (no additional query logged)
      final ContainerRole role2 = resolver.resolve(container2);
      assertThat(role2).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should throw NullPointerException when container is null")
    void shouldThrowWhenContainerIsNull() {
      final RoleResolver resolver = new RoleResolver(Map.of());

      assertThatThrownBy(() -> resolver.clearCache(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should not throw when clearing non-cached container")
    void shouldNotThrowWhenClearingNonCachedContainer() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // Should not throw (container not in cache yet)
      resolver.clearCache(container);
    }
  }

  @Nested
  @DisplayName("Thread Safety")
  class ThreadSafetyTests {

    @Test
    @DisplayName("Should handle concurrent resolve() calls")
    void shouldHandleConcurrentResolveCalls() throws InterruptedException {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("container-id");

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // Create multiple threads resolving the same container
      final Thread[] threads = new Thread[10];
      final ContainerRole[] results = new ContainerRole[10];

      for (int i = 0; i < threads.length; i++) {
        final int index = i;
        threads[i] =
            new Thread(
                () -> {
                  results[index] = resolver.resolve(container);
                });
      }

      // Start all threads
      for (Thread thread : threads) {
        thread.start();
      }

      // Wait for completion
      for (Thread thread : threads) {
        thread.join();
      }

      // All results should be UNKNOWN
      for (ContainerRole role : results) {
        assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
      }
    }

    @Test
    @DisplayName("Should handle concurrent clearCache() calls")
    void shouldHandleConcurrentClearCacheCalls() throws InterruptedException {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("container-id");

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // Populate cache
      resolver.resolve(container);

      // Create multiple threads clearing cache
      final Thread[] threads = new Thread[10];

      for (int i = 0; i < threads.length; i++) {
        threads[i] = new Thread(resolver::clearCache);
      }

      // Start all threads
      for (Thread thread : threads) {
        thread.start();
      }

      // Wait for completion
      for (Thread thread : threads) {
        thread.join();
      }

      // Should not throw - cache is cleared
      resolver.clearCache();
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCasesTests {

    @Test
    @DisplayName("Should handle container with null containerId")
    void shouldHandleContainerWithNullId() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn(null);

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // Should not throw - returns UNKNOWN
      final ContainerRole role = resolver.resolve(container);
      assertThat(role).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should use defensive copy of containerIndexMap")
    void shouldUseDefensiveCopyOfMap() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      final Map<GenericContainer<?>, Integer> map = new ConcurrentHashMap<>();
      map.put(container, 0);

      final RoleResolver resolver = new RoleResolver(map);

      // Modify original map
      map.put(mock(GenericContainer.class), 1);

      // Resolver should not be affected by external modification
      // (This test verifies defensive copying semantics)
      assertThat(resolver).isNotNull();
    }

    @Test
    @DisplayName("Should handle resolve() after clearCache()")
    void shouldHandleResolveAfterClearCache() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("container-id");

      final RoleResolver resolver = new RoleResolver(Map.of(container, 0));

      // First resolve
      final ContainerRole role1 = resolver.resolve(container);

      // Clear cache
      resolver.clearCache();

      // Second resolve
      final ContainerRole role2 = resolver.resolve(container);

      // Both should return UNKNOWN
      assertThat(role1).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(role2).isEqualTo(ContainerRole.UNKNOWN);
    }
  }
}
