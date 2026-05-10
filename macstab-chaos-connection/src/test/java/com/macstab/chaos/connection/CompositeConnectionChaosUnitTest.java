/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.spi.ConnectionChaosStrategy;

/**
 * Unit tests for {@link CompositeConnectionChaos} — pure delegation logic, no Docker.
 *
 * <p>Mocks {@link ConnectionChaosStrategy} to verify the composite's policy:
 *
 * <ul>
 *   <li>add operations → first applicable strategy wins
 *   <li>cleanup operations → fan-out to every applicable strategy, best-effort
 *   <li>aggregate exception only when every applicable strategy fails
 *   <li>{@code supports()} probe failures are treated as non-applicable, never propagated
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CompositeConnectionChaos (unit)")
class CompositeConnectionChaosUnitTest {

  private GenericContainer<?> container;
  private ConnectionChaosStrategy strategyA;
  private ConnectionChaosStrategy strategyB;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);

    strategyA = mock(ConnectionChaosStrategy.class);
    strategyB = mock(ConnectionChaosStrategy.class);
    when(strategyA.supports(any())).thenReturn(true);
    when(strategyB.supports(any())).thenReturn(true);
  }

  // ==================== Constructor ====================

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("rejects null strategy list")
    void rejectsNullList() {
      assertThatThrownBy(() -> new CompositeConnectionChaos(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("strategies");
    }

    @Test
    @DisplayName("rejects empty strategy list")
    void rejectsEmptyList() {
      assertThatThrownBy(() -> new CompositeConnectionChaos(Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("strategies must not be empty");
    }

    @Test
    @DisplayName("rejects list containing null element")
    void rejectsNullElement() {
      final List<ConnectionChaosStrategy> withNull = new ArrayList<>();
      withNull.add(strategyA);
      withNull.add(null);
      assertThatThrownBy(() -> new CompositeConnectionChaos(withNull))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("index 1");
    }

    @Test
    @DisplayName("defensively copies strategy list")
    void defensiveCopy() {
      final List<ConnectionChaosStrategy> mutable = new ArrayList<>();
      mutable.add(strategyA);
      final CompositeConnectionChaos composite = new CompositeConnectionChaos(mutable);

      mutable.clear();

      composite.addLatency(container, "host:1", Duration.ofMillis(10));
      verify(strategyA).addLatency(container, "host:1", Duration.ofMillis(10));
    }

    @Test
    @DisplayName("standard() factory wraps a Toxiproxy strategy")
    void standardFactory() {
      final CompositeConnectionChaos composite = CompositeConnectionChaos.standard();
      assertThat(composite).isNotNull();
      // supports() should not throw — it merely probes via the underlying strategy.
      assertThat(composite.supports(container)).isTrue();
    }
  }

  // ==================== Add operations ====================

  @Nested
  @DisplayName("Add operations: first applicable wins")
  class AddDelegationTests {

    @Test
    @DisplayName("addLatency delegates to the first supporting strategy only")
    void firstWins() {
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.addLatency(container, "db:5432", Duration.ofMillis(100));

      verify(strategyA).addLatency(container, "db:5432", Duration.ofMillis(100));
      verify(strategyB, never()).addLatency(any(), anyString(), any());
    }

    @Test
    @DisplayName("addLatency skips non-applicable strategies and uses next applicable")
    void skipsNonApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.addLatency(container, "db:5432", Duration.ofMillis(100));

      verify(strategyA, never()).addLatency(any(), anyString(), any());
      verify(strategyB).addLatency(container, "db:5432", Duration.ofMillis(100));
    }

    @Test
    @DisplayName("addLatency throws ChaosOperationFailedException when no strategy supports")
    void noApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      assertThatThrownBy(() -> composite.addLatency(container, "db:5432", Duration.ofMillis(10)))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("No applicable connection-chaos strategy");
    }

    @Test
    @DisplayName("dropPackets uses the same first-wins policy")
    void dropPacketsFirstWins() {
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.dropPackets(container, "redis:6379", 0.25);

      verify(strategyA).dropPackets(container, "redis:6379", 0.25);
      verify(strategyB, never())
          .dropPackets(any(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("rejectConnections uses the same first-wins policy")
    void rejectConnectionsFirstWins() {
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.rejectConnections(container, "api:8080");

      verify(strategyA).rejectConnections(container, "api:8080");
      verify(strategyB, never()).rejectConnections(any(), anyString());
    }
  }

  // ==================== Cleanup operations ====================

  @Nested
  @DisplayName("Cleanup operations: fan-out, best-effort")
  class RemoveFanoutTests {

    @Test
    @DisplayName("removeToxic invokes every applicable strategy")
    void fansOut() {
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.removeToxic(container, "db:5432", "latency");

      verify(strategyA).removeToxic(container, "db:5432", "latency");
      verify(strategyB).removeToxic(container, "db:5432", "latency");
    }

    @Test
    @DisplayName("removeAllToxics invokes every applicable strategy")
    void fansOutRemoveAll() {
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.removeAllToxics(container, "api:443");

      verify(strategyA).removeAllToxics(container, "api:443");
      verify(strategyB).removeAllToxics(container, "api:443");
    }

    @Test
    @DisplayName("removeToxic skips non-applicable strategies")
    void skipsNonApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.removeToxic(container, "db:5432", "down");

      verify(strategyA, never()).removeToxic(any(), anyString(), anyString());
      verify(strategyB).removeToxic(container, "db:5432", "down");
    }

    @Test
    @DisplayName("removeToxic survives one strategy throwing as long as another succeeded")
    void survivesPartialFailure() {
      doThrow(new ChaosOperationFailedException("bang"))
          .when(strategyA)
          .removeToxic(any(), anyString(), anyString());
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.removeToxic(container, "db:5432", "latency");

      verify(strategyA).removeToxic(container, "db:5432", "latency");
      verify(strategyB).removeToxic(container, "db:5432", "latency");
    }

    @Test
    @DisplayName("removeToxic aggregates exceptions when every applicable strategy fails")
    void aggregatesAllFailures() {
      final RuntimeException errA = new ChaosOperationFailedException("a-bang");
      final RuntimeException errB = new ChaosOperationFailedException("b-bang");
      doThrow(errA).when(strategyA).removeToxic(any(), anyString(), anyString());
      doThrow(errB).when(strategyB).removeToxic(any(), anyString(), anyString());
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      assertThatThrownBy(() -> composite.removeToxic(container, "db:5432", "latency"))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("removeToxic failed on every applicable strategy")
          .satisfies(
              t -> assertThat(t.getSuppressed()).hasSize(2).containsExactlyInAnyOrder(errA, errB));
    }

    @Test
    @DisplayName("cleanup is silent no-op when no strategy is applicable")
    void noApplicableIsNoOp() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.removeAllToxics(container, "db:5432");

      verify(strategyA, never()).removeAllToxics(any(), anyString());
      verify(strategyB, never()).removeAllToxics(any(), anyString());
    }

    @Test
    @DisplayName("reset fans out to every applicable strategy")
    void resetFansOut() {
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.reset(container);

      verify(strategyA).reset(container);
      verify(strategyB).reset(container);
    }

    @Test
    @DisplayName(
        "installTools is intentionally a no-op (lazy-install contract — strategies install on first use)")
    void installToolsNoOp() {
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.installTools(container);

      verify(strategyA, never()).installTools(any());
      verify(strategyB, never()).installTools(any());
    }
  }

  // ==================== Probe semantics ====================

  @Nested
  @DisplayName("supports() probe semantics")
  class SupportsProbeTests {

    @Test
    @DisplayName("composite supports() returns true when at least one strategy supports")
    void anySupports() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(true);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      assertThat(composite.supports(container)).isTrue();
    }

    @Test
    @DisplayName("composite supports() returns false when none supports")
    void noneSupports() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      assertThat(composite.supports(container)).isFalse();
    }

    @Test
    @DisplayName("strategy throwing inside supports() is treated as non-applicable")
    void supportsThrowingTreatedAsFalse() {
      when(strategyA.supports(any())).thenThrow(new IllegalStateException("probe boom"));
      when(strategyB.supports(any())).thenReturn(true);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      composite.addLatency(container, "db:5432", Duration.ofMillis(10));

      verify(strategyA, never()).addLatency(any(), anyString(), any());
      verify(strategyB).addLatency(container, "db:5432", Duration.ofMillis(10));
    }

    @Test
    @DisplayName("supports() rejects null container")
    void rejectsNullContainer() {
      final CompositeConnectionChaos composite = new CompositeConnectionChaos(List.of(strategyA));
      assertThatThrownBy(() -> composite.supports(null)).isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== isSupported (static) ====================

  @Nested
  @DisplayName("isSupported() reflects strategy availability")
  class IsSupportedTests {

    @Test
    @DisplayName("returns true when at least one strategy is supported")
    void anySupported() {
      when(strategyA.isSupported()).thenReturn(false);
      when(strategyB.isSupported()).thenReturn(true);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      assertThat(composite.isSupported()).isTrue();
    }

    @Test
    @DisplayName("returns false when no strategy is supported")
    void noneSupported() {
      when(strategyA.isSupported()).thenReturn(false);
      when(strategyB.isSupported()).thenReturn(false);
      final CompositeConnectionChaos composite =
          new CompositeConnectionChaos(List.of(strategyA, strategyB));

      assertThat(composite.isSupported()).isFalse();
    }
  }
}
