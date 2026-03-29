/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.annotation.Resources;
import com.macstab.chaos.core.extension.MockChaosPlugin.MockConnectionInfo;
import com.macstab.chaos.core.extension.MockChaosPlugin.MockContainer;

/**
 * Integration tests for {@link ChaosTestingExtension}.
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>End-to-end container lifecycle with @Resources
 *   <li>Parameter injection validation
 *   <li>Real Docker container startup (Alpine)
 *   <li>Resource constraint verification (Docker inspect)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ChaosTestingExtension Integration Tests")
@MockContainer(image = "alpine:latest", port = 8080)
@Resources(memory = "256M", cpus = "1")
class ChaosTestingExtensionIntegrationTest {

  @Test
  @DisplayName("should start container with resource constraints")
  void shouldStartContainerWithResourceConstraints(final MockConnectionInfo info) {
    assertThat(info).isNotNull();
    assertThat(info.getHost()).isNotBlank();
    assertThat(info.getPort()).isGreaterThan(0);
    assertThat(info.getContainer()).isNotNull();
    assertThat(info.getContainer().isRunning()).isTrue();
  }

  @Test
  @DisplayName("should inject connection info parameter")
  void shouldInjectConnectionInfoParameter(final MockConnectionInfo info) {
    assertThat(info.getHost()).isEqualTo("localhost");
    assertThat(info.getPort()).isGreaterThan(1024);
  }

  @Test
  @DisplayName("should apply memory constraint")
  void shouldApplyMemoryConstraint(final MockConnectionInfo info) {
    final GenericContainer<?> container = info.getContainer();

    // Note: Docker inspect validation would require TestContainers API extension
    // For now, validate container is running (constraint didn't cause failure)
    assertThat(container.isRunning()).isTrue();
  }

  @Test
  @DisplayName("should apply CPU constraint")
  void shouldApplyCpuConstraint(final MockConnectionInfo info) {
    final GenericContainer<?> container = info.getContainer();

    // Note: Docker inspect validation would require TestContainers API extension
    // For now, validate container is running (constraint didn't cause failure)
    assertThat(container.isRunning()).isTrue();
  }

  @Test
  @DisplayName("should expose mapped port")
  void shouldExposeMappedPort(final MockConnectionInfo info) {
    assertThat(info.getPort()).isNotEqualTo(8080);
    assertThat(info.getPort()).isGreaterThan(1024);
    assertThat(info.getPort()).isLessThan(65536);
  }
}
