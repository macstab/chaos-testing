/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

@DisplayName("RuleRegistry (unit)")
class RuleRegistryTest {

  private RuleRegistry registry;

  @SuppressWarnings("rawtypes")
  private GenericContainer containerA;

  @SuppressWarnings("rawtypes")
  private GenericContainer containerB;

  private IoRule rule;

  @BeforeEach
  void setUp() {
    registry = new RuleRegistry();
    containerA = mock(GenericContainer.class);
    containerB = mock(GenericContainer.class);
    rule = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 1.0);
  }

  @Test
  @DisplayName("register + snapshot returns the entry")
  void registerAndSnapshot() {
    final RuleHandle h = new RuleHandle("r1");
    registry.register(containerA, new RuleRegistry.Entry(h, rule));

    assertThat(registry.snapshot(containerA))
        .extracting(RuleRegistry.Entry::handle)
        .containsExactly(h);
  }

  @Test
  @DisplayName("snapshot is empty for unknown container")
  void snapshotEmptyForUnknown() {
    assertThat(registry.snapshot(containerA)).isEmpty();
  }

  @Test
  @DisplayName("remove returns the removed entry")
  void removeReturnsEntry() {
    final RuleHandle h = new RuleHandle("r1");
    registry.register(containerA, new RuleRegistry.Entry(h, rule));

    assertThat(registry.remove(containerA, h)).isPresent();
    assertThat(registry.snapshot(containerA)).isEmpty();
  }

  @Test
  @DisplayName("remove of unknown handle is empty Optional (idempotent)")
  void removeUnknownIsEmpty() {
    assertThat(registry.remove(containerA, new RuleHandle("r99"))).isEmpty();
  }

  @Test
  @DisplayName("removeAll clears the container and returns the entries")
  void removeAllClears() {
    final RuleHandle h1 = new RuleHandle("r1");
    final RuleHandle h2 = new RuleHandle("r2");
    registry.register(containerA, new RuleRegistry.Entry(h1, rule));
    registry.register(containerA, new RuleRegistry.Entry(h2, rule));

    assertThat(registry.removeAll(containerA)).hasSize(2);
    assertThat(registry.snapshot(containerA)).isEmpty();
  }

  @Test
  @DisplayName("removeAll on unknown container returns empty list (idempotent)")
  void removeAllUnknown() {
    assertThat(registry.removeAll(containerA)).isEmpty();
  }

  @Test
  @DisplayName("per-container isolation: removing from A does not affect B")
  void perContainerIsolation() {
    final RuleHandle hA = new RuleHandle("ra");
    final RuleHandle hB = new RuleHandle("rb");
    registry.register(containerA, new RuleRegistry.Entry(hA, rule));
    registry.register(containerB, new RuleRegistry.Entry(hB, rule));

    registry.removeAll(containerA);

    assertThat(registry.snapshot(containerA)).isEmpty();
    assertThat(registry.snapshot(containerB)).hasSize(1);
  }

  @Test
  @DisplayName("null arguments throw NPE on every accessor")
  void nullSafety() {
    final RuleHandle h = new RuleHandle("r1");
    assertThatThrownBy(() -> registry.register(null, new RuleRegistry.Entry(h, rule)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.register(containerA, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.remove(null, h)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.remove(containerA, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.removeAll(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.snapshot(null)).isInstanceOf(NullPointerException.class);
  }
}
