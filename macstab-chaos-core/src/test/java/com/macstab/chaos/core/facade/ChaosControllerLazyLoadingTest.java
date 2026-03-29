/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.facade;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;

/** Tests for ChaosController lazy loading and getter methods. */
@DisplayName("ChaosController - Lazy Loading Coverage")
class ChaosControllerLazyLoadingTest {

  @Test
  @DisplayName("cpu() should return non-null")
  void cpu_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.cpu()).isNotNull();
  }

  @Test
  @DisplayName("memory() should return non-null")
  void memory_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.memory()).isNotNull();
  }

  @Test
  @DisplayName("disk() should return non-null")
  void disk_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.disk()).isNotNull();
  }

  @Test
  @DisplayName("process() should return non-null")
  void process_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.process()).isNotNull();
  }

  @Test
  @DisplayName("network() should return non-null")
  void network_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.network()).isNotNull();
  }

  @Test
  @DisplayName("time() should return non-null")
  void time_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.time()).isNotNull();
  }

  @Test
  @DisplayName("dns() should return non-null")
  void dns_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.dns()).isNotNull();
  }

  @Test
  @DisplayName("connection() should return non-null")
  void connection_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.connection()).isNotNull();
  }

  @Test
  @DisplayName("cache() should return non-null")
  void cache_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.cache()).isNotNull();
  }

  @Test
  @DisplayName("filesystem() should return non-null")
  void filesystem_shouldReturnNonNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThat(controller.filesystem()).isNotNull();
  }

  @Test
  @DisplayName("Multiple calls should return same instance (lazy)")
  void multipleCalls_shouldReturnSameInstance() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    var cpu1 = controller.cpu();
    var cpu2 = controller.cpu();

    assertThat(cpu1).isSameAs(cpu2);
  }

  @Test
  @DisplayName("Should throw when container is null")
  void shouldThrowWhenContainerNull() {
    assertThatThrownBy(() -> new ChaosController(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("container must not be null");
  }

  @Test
  @DisplayName("withProbability() should reject rate < 0")
  void withProbability_shouldRejectNegativeRate() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThatThrownBy(() -> controller.withProbability(-0.1, 42))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rate must be in [0.0, 1.0]");
  }

  @Test
  @DisplayName("withProbability() should reject rate > 1.0")
  void withProbability_shouldRejectRateAboveOne() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThatThrownBy(() -> controller.withProbability(1.1, 42))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rate must be in [0.0, 1.0]");
  }

  @Test
  @DisplayName("withProbability() should accept 0.0")
  void withProbability_shouldAcceptZero() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThatCode(() -> controller.withProbability(0.0, 42)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("withProbability() should accept 1.0")
  void withProbability_shouldAcceptOne() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThatCode(() -> controller.withProbability(1.0, 42)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("withProbability() should accept valid range")
  void withProbability_shouldAcceptValidRange() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    assertThatCode(() -> controller.withProbability(0.5, 42)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("withProbability() should return new controller")
  void withProbability_shouldReturnNewController() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container);

    ChaosController probabilistic = controller.withProbability(0.5, 42);

    assertThat(probabilistic).isNotNull();
    assertThat(probabilistic).isNotSameAs(controller);
  }

  @Test
  @DisplayName("Probabilistic controller should have working getters")
  void probabilisticController_shouldHaveWorkingGetters() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container).withProbability(0.5, 42);

    assertThat(controller.cpu()).isNotNull();
    assertThat(controller.memory()).isNotNull();
    assertThat(controller.network()).isNotNull();
  }

  @Test
  @DisplayName("Probabilistic controller should wrap all providers")
  void probabilisticController_shouldWrapAllProviders() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController controller = new ChaosController(container).withProbability(0.5, 42);

    // Call all 10 getters to trigger probabilistic wrapping paths
    assertThat(controller.cpu()).isNotNull();
    assertThat(controller.memory()).isNotNull();
    assertThat(controller.disk()).isNotNull();
    assertThat(controller.process()).isNotNull();
    assertThat(controller.network()).isNotNull();
    assertThat(controller.time()).isNotNull();
    assertThat(controller.dns()).isNotNull();
    assertThat(controller.connection()).isNotNull();
    assertThat(controller.cache()).isNotNull();
    assertThat(controller.filesystem()).isNotNull();
  }

  @Test
  @DisplayName("Probabilistic getters should return wrapped instances")
  void probabilisticGetters_shouldReturnWrappedInstances() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    ChaosController deterministic = new ChaosController(container);
    ChaosController probabilistic = new ChaosController(container).withProbability(0.5, 42);

    // Deterministic returns direct instances
    var detCpu = deterministic.cpu();

    // Probabilistic returns wrapped instances (proxies)
    var probCpu = probabilistic.cpu();

    assertThat(detCpu).isNotNull();
    assertThat(probCpu).isNotNull();
    // Wrapped instances are proxies, so class differs
    assertThat(probCpu.getClass()).isNotEqualTo(detCpu.getClass());
  }
}
