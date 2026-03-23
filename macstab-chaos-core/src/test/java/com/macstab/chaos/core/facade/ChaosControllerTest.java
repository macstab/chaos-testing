/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.defaults.NoOpCpuChaos;
import com.macstab.chaos.core.defaults.NoOpDiskChaos;
import com.macstab.chaos.core.defaults.NoOpDnsChaos;
import com.macstab.chaos.core.defaults.NoOpMemoryChaos;
import com.macstab.chaos.core.defaults.NoOpNetworkChaos;
import com.macstab.chaos.core.defaults.NoOpProcessChaos;
import com.macstab.chaos.core.defaults.NoOpTimeChaos;

class ChaosControllerTest {

  @Test
  void shouldRejectNullContainer() {
    assertThatThrownBy(() -> new ChaosController(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("container must not be null");
  }

  @Test
  void shouldCreateControllerWithValidContainer() {
    // Given
    final GenericContainer<?> container = mock(GenericContainer.class);

    // When
    final ChaosController controller = new ChaosController(container);

    // Then
    assertThat(controller).isNotNull();
    assertThat(controller.getContainer()).isSameAs(container);
  }

  @Test
  void shouldLazyLoadCpuChaos() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When
    final var cpu1 = controller.cpu();
    final var cpu2 = controller.cpu();

    // Then
    assertThat(cpu1).isInstanceOf(NoOpCpuChaos.class);
    assertThat(cpu1).isSameAs(cpu2); // Same instance (lazy-loaded + cached)
  }

  @Test
  void shouldLazyLoadMemoryChaos() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When
    final var memory = controller.memory();

    // Then
    assertThat(memory).isInstanceOf(NoOpMemoryChaos.class);
  }

  @Test
  void shouldLazyLoadDiskChaos() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When
    final var disk = controller.disk();

    // Then
    assertThat(disk).isInstanceOf(NoOpDiskChaos.class);
  }

  @Test
  void shouldLazyLoadProcessChaos() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When
    final var process = controller.process();

    // Then
    assertThat(process).isInstanceOf(NoOpProcessChaos.class);
  }

  @Test
  void shouldLazyLoadNetworkChaos() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When
    final var network = controller.network();

    // Then
    assertThat(network).isInstanceOf(NoOpNetworkChaos.class);
  }

  @Test
  void shouldLazyLoadTimeChaos() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When
    final var time = controller.time();

    // Then
    assertThat(time).isInstanceOf(NoOpTimeChaos.class);
  }

  @Test
  void shouldLazyLoadDnsChaos() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When
    final var dns = controller.dns();

    // Then
    assertThat(dns).isInstanceOf(NoOpDnsChaos.class);
  }

  @Test
  void resetAllShouldNotFailWhenNoProvidersLoaded() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));

    // When/Then (should not throw)
    controller.resetAll();
  }

  @Test
  void resetAllShouldResetLoadedProviders() {
    // Given
    final ChaosController controller = new ChaosController(mock(GenericContainer.class));
    controller.cpu(); // Load CPU provider
    controller.memory(); // Load memory provider

    // When/Then (should call reset on loaded providers, no-ops don't throw on reset)
    controller.resetAll();
  }
}
