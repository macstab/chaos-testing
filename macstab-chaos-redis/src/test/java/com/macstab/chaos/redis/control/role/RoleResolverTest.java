/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link RoleResolver}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleResolver")
class RoleResolverTest {

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw for null containerIndexMap")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> new RoleResolver(null))
          .isInstanceOf(NullPointerException.class);
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
      assertThatThrownBy(() -> resolver.resolve(null))
          .isInstanceOf(NullPointerException.class);
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
      // (container is stopped, returns UNKNOWN again)
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

      // ASSERT: c1 is re-resolved (returns UNKNOWN), c2 from cache (returns UNKNOWN)
      assertThat(resolver.resolve(c1)).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(resolver.resolve(c2)).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Should throw for null container in clearCache(container)")
    void shouldThrowForNullContainer() {
      final RoleResolver resolver = new RoleResolver(Map.of());
      assertThatThrownBy(() -> resolver.clearCache(null))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
