/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.extension.MockChaosPlugin.*;

/**
 * Coverage tests for {@link ChaosContainers} and {@link ContainerManager}.
 *
 * <p>Both are thin facades over {@link ChaosTestingExtension} — tested via real
 * container lifecycle inside a {@code @Nested} test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("API - ChaosContainers + ContainerManager")
class ApiCoverageTest {

  // ──────────────────────────────────────────────────────────────────────────
  // ChaosContainers — all four static methods
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("ChaosContainers - all access patterns")
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  class ChaosContainersTest {

    @Test
    @DisplayName("get() returns connection info by annotation type and id")
    void get_byAnnotationType(MockConnectionInfo info) {
      MockConnectionInfo result = ChaosContainers.get(MockContainer.class, "default");
      assertThat(result).isNotNull();
      assertThat(result).isEqualTo(info);
    }

    @Test
    @DisplayName("getAll() returns all instances for annotation type")
    void getAll_byAnnotationType(MockConnectionInfo info) {
      List<MockConnectionInfo> all = ChaosContainers.getAll(MockContainer.class);
      assertThat(all).hasSize(1);
      assertThat(all.get(0)).isEqualTo(info);
    }

    @Test
    @DisplayName("getAllByBaseType() returns all instances implementing base interface")
    void getAllByBaseType(MockConnectionInfo info) {
      List<MockConnectionBase> all = ChaosContainers.getAllByBaseType(MockConnectionBase.class);
      assertThat(all).hasSize(1);
      assertThat(all.get(0)).isEqualTo(info);
    }

    @Test
    @DisplayName("getByBaseType() returns connection info by base interface and id")
    void getByBaseType(MockConnectionInfo info) {
      MockConnectionBase result = ChaosContainers.getByBaseType(MockConnectionBase.class, "default");
      assertThat(result).isNotNull();
      assertThat(result).isEqualTo(info);
    }

    @Test
    @DisplayName("ChaosContainers constructor throws UnsupportedOperationException")
    void constructor_throwsUnsupportedOperation() {
      assertThatThrownBy(() -> {
        var ctor = ChaosContainers.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
      }).cause().isInstanceOf(UnsupportedOperationException.class);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ContainerManager — constructor, get(), getAll()
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("ContainerManager - typed facade")
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  class ContainerManagerTest {

    @Test
    @DisplayName("get() delegates to getter function")
    void get_delegatesToGetter(MockConnectionInfo info) {
      ContainerManager<MockConnectionInfo> manager = new ContainerManager<>(
          id -> ChaosContainers.get(MockContainer.class, id),
          ()  -> ChaosContainers.getAll(MockContainer.class));

      MockConnectionInfo result = manager.get("default");
      assertThat(result).isNotNull();
      assertThat(result).isEqualTo(info);
    }

    @Test
    @DisplayName("getAll() delegates to allGetter function")
    void getAll_delegatesToAllGetter(MockConnectionInfo info) {
      ContainerManager<MockConnectionInfo> manager = new ContainerManager<>(
          id -> ChaosContainers.get(MockContainer.class, id),
          ()  -> ChaosContainers.getAll(MockContainer.class));

      List<MockConnectionInfo> all = manager.getAll();
      assertThat(all).hasSize(1);
      assertThat(all.get(0)).isEqualTo(info);
    }

    @Test
    @DisplayName("constructor rejects null getter")
    void constructor_rejectsNullGetter() {
      assertThatThrownBy(() -> new ContainerManager<>(null, List::of))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects null allGetter")
    void constructor_rejectsNullAllGetter() {
      assertThatThrownBy(() -> new ContainerManager<>(id -> null, null))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
