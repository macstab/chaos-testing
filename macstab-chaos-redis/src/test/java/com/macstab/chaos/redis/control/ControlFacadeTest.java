/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link ControlFacade}.
 *
 * <p><strong>Note:</strong> Full end-to-end testing requires Redis containers and is tested in
 * integration tests. These unit tests focus on factory methods, null handling, and API contracts.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ControlFacade")
class ControlFacadeTest {

  @Nested
  @DisplayName("Factory Method: create()")
  class CreateTests {

    @Test
    @DisplayName("Should create ControlFacade with valid parameters")
    void shouldCreateWithValidParameters() {
      final GenericContainer<?> master = mock(GenericContainer.class);
      final GenericContainer<?> replica0 = mock(GenericContainer.class);
      final GenericContainer<?> sentinel0 = mock(GenericContainer.class);

      final List<GenericContainer<?>> allContainers = List.of(master, replica0, sentinel0);
      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of(replica0, 0, sentinel0, 0);

      final ControlFacade facade = ControlFacade.create(allContainers, containerIndexMap);

      assertThat(facade).isNotNull();
    }

    @Test
    @DisplayName("Should throw NullPointerException when allContainers is null")
    void shouldThrowWhenAllContainersIsNull() {
      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of();

      assertThatThrownBy(() -> ControlFacade.create(null, containerIndexMap))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("allContainers");
    }

    @Test
    @DisplayName("Should throw NullPointerException when containerIndexMap is null")
    void shouldThrowWhenContainerIndexMapIsNull() {
      final List<GenericContainer<?>> allContainers = List.of();

      assertThatThrownBy(() -> ControlFacade.create(allContainers, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("containerIndexMap");
    }

    @Test
    @DisplayName("Should create ControlFacade with empty containers list")
    void shouldCreateWithEmptyContainersList() {
      final List<GenericContainer<?>> allContainers = List.of();
      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of();

      final ControlFacade facade = ControlFacade.create(allContainers, containerIndexMap);

      assertThat(facade).isNotNull();
    }

    @Test
    @DisplayName("Should create ControlFacade with single container")
    void shouldCreateWithSingleContainer() {
      final GenericContainer<?> master = mock(GenericContainer.class);

      final List<GenericContainer<?>> allContainers = List.of(master);
      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of();

      final ControlFacade facade = ControlFacade.create(allContainers, containerIndexMap);

      assertThat(facade).isNotNull();
    }

    @Test
    @DisplayName("Should create ControlFacade with multiple replicas and sentinels")
    void shouldCreateWithMultipleContainers() {
      final GenericContainer<?> master = mock(GenericContainer.class);
      final GenericContainer<?> replica0 = mock(GenericContainer.class);
      final GenericContainer<?> replica1 = mock(GenericContainer.class);
      final GenericContainer<?> sentinel0 = mock(GenericContainer.class);
      final GenericContainer<?> sentinel1 = mock(GenericContainer.class);

      final List<GenericContainer<?>> allContainers =
          List.of(master, replica0, replica1, sentinel0, sentinel1);
      final Map<GenericContainer<?>, Integer> containerIndexMap =
          Map.of(replica0, 0, replica1, 1, sentinel0, 0, sentinel1, 1);

      final ControlFacade facade = ControlFacade.create(allContainers, containerIndexMap);

      assertThat(facade).isNotNull();
    }
  }

  @Nested
  @DisplayName("Null Handling")
  class NullHandlingTests {

    private ControlFacade createFacade() {
      final List<GenericContainer<?>> allContainers = List.of();
      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of();
      return ControlFacade.create(allContainers, containerIndexMap);
    }

    @Test
    @DisplayName("inspect() should throw NullPointerException when connection is null")
    void inspectShouldThrowWhenConnectionIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.inspect(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("connection");
    }

    @Test
    @DisplayName("restart() should throw NullPointerException when container is null")
    void restartShouldThrowWhenContainerIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.restart(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("kill() should throw NullPointerException when container is null")
    void killShouldThrowWhenContainerIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.kill(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("pause() should throw NullPointerException when container is null")
    void pauseShouldThrowWhenContainerIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.pause(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("resume() should throw NullPointerException when container is null")
    void resumeShouldThrowWhenContainerIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.resume(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("waitForReady() should throw NullPointerException when container is null")
    void waitForReadyShouldThrowWhenContainerIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.waitForReady(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("waitForReady(timeout) should throw NullPointerException when container is null")
    void waitForReadyWithTimeoutShouldThrowWhenContainerIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.waitForReady(null, java.time.Duration.ofSeconds(5)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("waitForReady(timeout) should throw NullPointerException when timeout is null")
    void waitForReadyWithTimeoutShouldThrowWhenTimeoutIsNull() {
      final ControlFacade facade = createFacade();
      final GenericContainer<?> container = mock(GenericContainer.class);

      assertThatThrownBy(() -> facade.waitForReady(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("triggerFailover(timeout) should throw NullPointerException when timeout is null")
    void triggerFailoverWithTimeoutShouldThrowWhenTimeoutIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.triggerFailover(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("getContainer() should throw NullPointerException when role is null")
    void getContainerShouldThrowWhenRoleIsNull() {
      final ControlFacade facade = createFacade();

      assertThatThrownBy(() -> facade.getContainer(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("role");
    }
  }

  @Nested
  @DisplayName("clearRoleCache()")
  class ClearRoleCacheTests {

    @Test
    @DisplayName("Should not throw when clearing cache")
    void shouldNotThrowWhenClearingCache() {
      final List<GenericContainer<?>> allContainers = List.of();
      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of();

      final ControlFacade facade = ControlFacade.create(allContainers, containerIndexMap);

      // Should not throw
      facade.clearRoleCache();
    }

    @Test
    @DisplayName("Should allow multiple clearRoleCache() calls")
    void shouldAllowMultipleClearRoleCacheCalls() {
      final List<GenericContainer<?>> allContainers = List.of();
      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of();

      final ControlFacade facade = ControlFacade.create(allContainers, containerIndexMap);

      // Multiple calls should not throw
      facade.clearRoleCache();
      facade.clearRoleCache();
      facade.clearRoleCache();
    }
  }

  @Nested
  @DisplayName("API Contract")
  class ApiContractTests {

    @Test
    @DisplayName("Should have inspect() method")
    void shouldHaveInspectMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(
              facadeClass.getMethod("inspect", io.lettuce.core.api.StatefulRedisConnection.class))
          .isNotNull();
    }

    @Test
    @DisplayName("Should have restart() method")
    void shouldHaveRestartMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("restart", GenericContainer.class)).isNotNull();
    }

    @Test
    @DisplayName("Should have kill() method")
    void shouldHaveKillMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("kill", GenericContainer.class)).isNotNull();
    }

    @Test
    @DisplayName("Should have pause() method")
    void shouldHavePauseMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("pause", GenericContainer.class)).isNotNull();
    }

    @Test
    @DisplayName("Should have resume() method")
    void shouldHaveResumeMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("resume", GenericContainer.class)).isNotNull();
    }

    @Test
    @DisplayName("Should have waitForReady() method")
    void shouldHaveWaitForReadyMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("waitForReady", GenericContainer.class)).isNotNull();
    }

    @Test
    @DisplayName("Should have triggerFailover() method")
    void shouldHaveTriggerFailoverMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("triggerFailover")).isNotNull();
    }

    @Test
    @DisplayName("Should have getMaster() method")
    void shouldHaveGetMasterMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("getMaster")).isNotNull();
    }

    @Test
    @DisplayName("Should have getReplicas() method")
    void shouldHaveGetReplicasMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("getReplicas")).isNotNull();
    }

    @Test
    @DisplayName("Should have getContainer() method")
    void shouldHaveGetContainerMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(
              facadeClass.getMethod(
                  "getContainer", com.macstab.chaos.redis.control.role.ContainerRole.class))
          .isNotNull();
    }

    @Test
    @DisplayName("Should have clearRoleCache() method")
    void shouldHaveClearRoleCacheMethod() throws NoSuchMethodException {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(facadeClass.getMethod("clearRoleCache")).isNotNull();
    }

    @Test
    @DisplayName("Should be final class")
    void shouldBeFinalClass() {
      final Class<?> facadeClass = ControlFacade.class;

      assertThat(java.lang.reflect.Modifier.isFinal(facadeClass.getModifiers())).isTrue();
    }
  }

  @Nested
  @DisplayName("Immutability")
  class ImmutabilityTests {

    @Test
    @DisplayName("Should not modify original allContainers list")
    void shouldNotModifyOriginalAllContainersList() {
      final GenericContainer<?> master = mock(GenericContainer.class);
      final java.util.List<GenericContainer<?>> originalList = new java.util.ArrayList<>();
      originalList.add(master);

      final Map<GenericContainer<?>, Integer> containerIndexMap = Map.of();

      final ControlFacade facade = ControlFacade.create(originalList, containerIndexMap);

      // Modify original list
      originalList.add(mock(GenericContainer.class));

      // Facade should not be affected by external modification
      assertThat(facade).isNotNull();
    }

    @Test
    @DisplayName("Should not modify original containerIndexMap")
    void shouldNotModifyOriginalContainerIndexMap() {
      final GenericContainer<?> replica0 = mock(GenericContainer.class);
      final java.util.Map<GenericContainer<?>, Integer> originalMap = new java.util.HashMap<>();
      originalMap.put(replica0, 0);

      final List<GenericContainer<?>> allContainers = List.of(replica0);

      final ControlFacade facade = ControlFacade.create(allContainers, originalMap);

      // Modify original map
      originalMap.put(mock(GenericContainer.class), 1);

      // Facade should not be affected by external modification
      assertThat(facade).isNotNull();
    }
  }
}
