/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.control.role.ContainerRole;

/**
 * Unit tests for {@link ControlFacade}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ControlFacade Factory Validation")
class ControlFacadeTest {

  @Nested
  @DisplayName("Factory method validation")
  class FactoryMethodValidation {

    @Test
    @DisplayName("Should throw for null allContainers")
    void shouldThrowForNullContainers() {
      assertThatThrownBy(() -> ControlFacade.create(null, Map.of()))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw for null containerIndexMap")
    void shouldThrowForNullIndexMap() {
      assertThatThrownBy(() -> ControlFacade.create(List.of(), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("getContainer() role-based access")
  class GetContainerByRole {

    @Test
    @DisplayName("Should throw ISE when no container has the requested role")
    void shouldThrowWhenNoContainerHasRole() {
      // ARRANGE: Create ControlFacade with a stopped container (role=UNKNOWN)
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      final ControlFacade facade =
          ControlFacade.create(List.of(container), Map.of(container, 0));

      // ACT & ASSERT
      assertThatThrownBy(() -> facade.getContainer(ContainerRole.MASTER))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("MASTER");
    }

    @Test
    @DisplayName("Should throw NPE for null role")
    void shouldThrowForNullRole() {
      final ControlFacade facade = ControlFacade.create(List.of(), Map.of());
      assertThatThrownBy(() -> facade.getContainer(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("triggerFailover() validation")
  class TriggerFailoverValidation {

    @Test
    @DisplayName("Should throw NPE for null timeout")
    void shouldThrowForNullTimeout() {
      final ControlFacade facade = ControlFacade.create(List.of(), Map.of());
      assertThatThrownBy(() -> facade.triggerFailover((Duration) null))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
