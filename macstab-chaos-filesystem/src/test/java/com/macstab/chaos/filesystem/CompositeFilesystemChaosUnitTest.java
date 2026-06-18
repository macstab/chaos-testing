/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.spi.FilesystemChaosStrategy;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

@DisplayName("CompositeFilesystemChaos (unit)")
class CompositeFilesystemChaosUnitTest {

  private GenericContainer<?> container;
  private FilesystemChaosStrategy strategyA;
  private FilesystemChaosStrategy strategyB;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    strategyA = mock(FilesystemChaosStrategy.class);
    strategyB = mock(FilesystemChaosStrategy.class);
    when(strategyA.supports(any())).thenReturn(true);
    when(strategyB.supports(any())).thenReturn(true);
  }

  // ==================== Constructor ====================

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("rejects null list")
    void rejectsNullList() {
      assertThatThrownBy(() -> new CompositeFilesystemChaos(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects empty list")
    void rejectsEmptyList() {
      assertThatThrownBy(() -> new CompositeFilesystemChaos(Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("rejects null strategy element")
    void rejectsNullElement() {
      final java.util.ArrayList<FilesystemChaosStrategy> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> new CompositeFilesystemChaos(withNull))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== Capability fall-through ====================

  @Nested
  @DisplayName("fillDisk — first-applicable wins with fall-through")
  class FillDiskRouting {

    @Test
    @DisplayName("first applicable strategy handles it")
    void firstHandles() {
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      composite.fillDisk(container, "10M");

      verify(strategyA).fillDisk(container, "10M");
      verify(strategyB, never()).fillDisk(any(), any());
    }

    @Test
    @DisplayName("falls through on ChaosUnsupportedOperationException")
    void fallsThroughOnUnsupported() {
      doThrow(new ChaosUnsupportedOperationException("nope"))
          .when(strategyA)
          .fillDisk(any(), any());
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      composite.fillDisk(container, "10M");

      verify(strategyA).fillDisk(container, "10M");
      verify(strategyB).fillDisk(container, "10M");
    }

    @Test
    @DisplayName("no applicable strategy → ChaosOperationFailedException")
    void noApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      assertThatThrownBy(() -> composite.fillDisk(container, "10M"))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("No applicable");
    }

    @Test
    @DisplayName("every applicable strategy unsupported → aggregate failure")
    void everyUnsupported() {
      doThrow(new ChaosUnsupportedOperationException("a")).when(strategyA).fillDisk(any(), any());
      doThrow(new ChaosUnsupportedOperationException("b")).when(strategyB).fillDisk(any(), any());
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      assertThatThrownBy(() -> composite.fillDisk(container, "10M"))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("unsupported");
    }
  }

  @Nested
  @DisplayName("injectPermissionErrors — same routing policy as fillDisk")
  class PermissionRouting {

    @Test
    @DisplayName("falls through on ChaosUnsupportedOperationException")
    void fallsThrough() {
      doThrow(new ChaosUnsupportedOperationException("nope"))
          .when(strategyA)
          .injectPermissionErrors(any(), any(), org.mockito.ArgumentMatchers.anyDouble());
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      composite.injectPermissionErrors(container, "/data", 1.0);

      verify(strategyA).injectPermissionErrors(container, "/data", 1.0);
      verify(strategyB).injectPermissionErrors(container, "/data", 1.0);
    }
  }

  // ==================== fan-out cleanup ====================

  @Nested
  @DisplayName("reset — fan-out, best-effort")
  class ResetFanOut {

    @Test
    @DisplayName("invokes reset on every applicable strategy")
    void fansOut() {
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      composite.reset(container);

      verify(strategyA).reset(container);
      verify(strategyB).reset(container);
    }

    @Test
    @DisplayName("aggregate exception only when EVERY applicable strategy fails")
    void aggregateOnAllFail() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      doThrow(new ChaosOperationFailedException("b")).when(strategyB).reset(any());
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      assertThatThrownBy(() -> composite.reset(container))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    @DisplayName("partial failure does not throw")
    void partialFailure() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      composite.reset(container); // must not throw

      verify(strategyB).reset(container);
    }
  }

  // ==================== advanced() accessor ====================

  @Nested
  @DisplayName("advanced()")
  class AdvancedAccessor {

    @Test
    @DisplayName("returns the AdvancedFilesystemChaos-implementing strategy")
    void returnsAdvanced() {
      final AdvancedFilesystemChaos advancedStrategy =
          mock(
              AdvancedFilesystemChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(FilesystemChaosStrategy.class));
      when(((FilesystemChaosStrategy) advancedStrategy).supports(any())).thenReturn(true);

      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(
              List.of((FilesystemChaosStrategy) advancedStrategy, strategyB));

      assertThat(composite.advanced()).isSameAs(advancedStrategy);
    }

    @Test
    @DisplayName("throws when no advanced strategy is registered")
    void noAdvancedRegistered() {
      final CompositeFilesystemChaos composite = new CompositeFilesystemChaos(List.of(strategyA));

      assertThatThrownBy(composite::advanced)
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("AdvancedFilesystemChaos");
    }

    @Test
    @DisplayName("advanced strategy can apply rules end-to-end through accessor")
    void applyViaAccessor() {
      final AdvancedFilesystemChaos advancedStrategy =
          mock(
              AdvancedFilesystemChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(FilesystemChaosStrategy.class));
      when(((FilesystemChaosStrategy) advancedStrategy).supports(any())).thenReturn(true);
      final RuleHandle expected = new RuleHandle("r1");
      final IoRule rule = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 0.5);
      when(advancedStrategy.apply(container, rule)).thenReturn(expected);

      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(
              List.of((FilesystemChaosStrategy) advancedStrategy, strategyB));

      assertThat(composite.advanced().apply(container, rule)).isSameAs(expected);
    }
  }

  // ==================== installTools / isSupported ====================

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("installTools is a no-op")
    void installToolsNoOp() {
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      composite.installTools(container);

      verify(strategyA, never()).installTools(any());
      verify(strategyB, never()).installTools(any());
    }

    @Test
    @DisplayName("isSupported true if any strategy is supported")
    void isSupportedAggregate() {
      when(strategyA.isSupported()).thenReturn(false);
      when(strategyB.isSupported()).thenReturn(true);
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      assertThat(composite.isSupported()).isTrue();
    }

    @Test
    @DisplayName("supports() probe failure treated as non-applicable")
    void probeFailureNonApplicable() {
      when(strategyA.supports(any())).thenThrow(new RuntimeException("boom"));
      final CompositeFilesystemChaos composite =
          new CompositeFilesystemChaos(List.of(strategyA, strategyB));

      composite.fillDisk(container, "10M");

      verify(strategyA, never()).fillDisk(any(), any());
      verify(strategyB).fillDisk(container, "10M");
    }
  }
}
