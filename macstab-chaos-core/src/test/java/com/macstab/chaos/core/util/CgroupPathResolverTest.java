/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

class CgroupPathResolverTest {

  @Test
  void shouldRejectNullContainer() {
    assertThatThrownBy(() -> CgroupPathResolver.resolve(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("container must not be null");
  }

  @Test
  void shouldRejectContainerWithNullId() {
    // Given
    final GenericContainer<?> container = mock(GenericContainer.class);
    when(container.getContainerId()).thenReturn(null);

    // When/Then
    assertThatThrownBy(() -> CgroupPathResolver.resolve(container))
        .isInstanceOf(ChaosOperationFailedException.class)
        .hasMessageContaining("Container ID is null or empty");
  }

  @Test
  void shouldRejectContainerWithEmptyId() {
    // Given
    final GenericContainer<?> container = mock(GenericContainer.class);
    when(container.getContainerId()).thenReturn("");

    // When/Then
    assertThatThrownBy(() -> CgroupPathResolver.resolve(container))
        .isInstanceOf(ChaosOperationFailedException.class)
        .hasMessageContaining("Container ID is null or empty");
  }

  @Test
  void shouldThrowHelpfulErrorWhenPathNotFound() {
    // Given
    final GenericContainer<?> container = mock(GenericContainer.class);
    when(container.getContainerId()).thenReturn("nonexistent-container-id");

    // When/Then
    assertThatThrownBy(() -> CgroupPathResolver.resolve(container))
        .isInstanceOf(ChaosOperationFailedException.class)
        .hasMessageContaining("Could not resolve cgroup path")
        .hasMessageContaining("nonexistent-container-id")
        .hasMessageContaining("/sys/fs/cgroup/system.slice/docker-")
        .hasMessageContaining("/sys/fs/cgroup/docker/");
  }

  // Note: Real container tests require running Docker container
  // Integration test in separate test class (CgroupPathResolverIntegrationTest)
}
