/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.spi.DnsChaosStrategy;
import com.macstab.chaos.dns.api.AdvancedDnsChaos;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

@DisplayName("CompositeDnsChaos (unit)")
class CompositeDnsChaosUnitTest {

  private GenericContainer<?> container;
  private DnsChaosStrategy strategyA;
  private DnsChaosStrategy strategyB;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    strategyA = mock(DnsChaosStrategy.class);
    strategyB = mock(DnsChaosStrategy.class);
    when(strategyA.supports(any())).thenReturn(true);
    when(strategyB.supports(any())).thenReturn(true);
  }

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {
    @Test
    @DisplayName("rejects null / empty / null-element")
    void rejects() {
      assertThatThrownBy(() -> new CompositeDnsChaos(null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new CompositeDnsChaos(Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class);
      final java.util.ArrayList<DnsChaosStrategy> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> new CompositeDnsChaos(withNull))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("First-applicable routing")
  class Routing {
    @Test
    @DisplayName("first applicable handles blockResolution; later strategy untouched")
    void firstWins() {
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      composite.blockResolution(container, "h");
      verify(strategyA).blockResolution(container, "h");
      verify(strategyB, never()).blockResolution(any(), any());
    }

    @Test
    @DisplayName("delayResolution routes to first applicable")
    void delayRoutes() {
      when(strategyA.supports(any())).thenReturn(false);
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      composite.delayResolution(container, Duration.ofMillis(50));
      verify(strategyA, never()).delayResolution(any(), any());
      verify(strategyB).delayResolution(container, Duration.ofMillis(50));
    }

    @Test
    @DisplayName("no applicable → ChaosOperationFailedException")
    void noApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.blockResolution(container, "h"))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("No applicable");
    }

    @Test
    @DisplayName("supports() probe failure treated as non-applicable")
    void probeFailure() {
      when(strategyA.supports(any())).thenThrow(new RuntimeException("boom"));
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      composite.blockResolution(container, "h");
      verify(strategyA, never()).blockResolution(any(), any());
      verify(strategyB).blockResolution(container, "h");
    }
  }

  @Nested
  @DisplayName("reset — fan-out, best-effort")
  class ResetFanOut {
    @Test
    @DisplayName("invokes reset on every applicable strategy")
    void fansOut() {
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyA).reset(container);
      verify(strategyB).reset(container);
    }

    @Test
    @DisplayName("aggregate exception when EVERY applicable fails")
    void aggregate() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      doThrow(new ChaosOperationFailedException("b")).when(strategyB).reset(any());
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.reset(container))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    @DisplayName("partial failure does not throw")
    void partial() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyB).reset(container);
    }
  }

  @Nested
  @DisplayName("advanced()")
  class AdvancedAccessor {
    @Test
    @DisplayName("returns the AdvancedDnsChaos-implementing strategy")
    void returnsAdvanced() {
      final AdvancedDnsChaos advancedStrategy =
          mock(
              AdvancedDnsChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(DnsChaosStrategy.class));
      when(((DnsChaosStrategy) advancedStrategy).supports(any())).thenReturn(true);
      final CompositeDnsChaos composite =
          new CompositeDnsChaos(List.of((DnsChaosStrategy) advancedStrategy, strategyB));
      assertThat(composite.advanced()).isSameAs(advancedStrategy);
    }

    @Test
    @DisplayName("throws when no advanced strategy registered")
    void noAdvanced() {
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA));
      assertThatThrownBy(composite::advanced)
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("AdvancedDnsChaos");
    }

    @Test
    @DisplayName("advanced.apply routes through accessor")
    void applyViaAccessor() {
      final AdvancedDnsChaos advancedStrategy =
          mock(
              AdvancedDnsChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(DnsChaosStrategy.class));
      when(((DnsChaosStrategy) advancedStrategy).supports(any())).thenReturn(true);
      final RuleHandle expected = new RuleHandle("r1");
      final DnsRule rule = DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL);
      when(advancedStrategy.apply(container, rule)).thenReturn(expected);
      final CompositeDnsChaos composite =
          new CompositeDnsChaos(List.of((DnsChaosStrategy) advancedStrategy, strategyB));
      assertThat(composite.advanced().apply(container, rule)).isSameAs(expected);
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {
    @Test
    @DisplayName("installTools fans out to every applicable")
    void installTools() {
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      composite.installTools(container);
      verify(strategyA).installTools(container);
      verify(strategyB).installTools(container);
    }

    @Test
    @DisplayName("installTools tolerates per-strategy failure")
    void installToolsTolerant() {
      doThrow(new RuntimeException("a")).when(strategyA).installTools(any());
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      composite.installTools(container);
      verify(strategyB).installTools(container);
    }

    @Test
    @DisplayName("isSupported true if any strategy supported")
    void isSupported() {
      when(strategyA.isSupported()).thenReturn(false);
      when(strategyB.isSupported()).thenReturn(true);
      final CompositeDnsChaos composite = new CompositeDnsChaos(List.of(strategyA, strategyB));
      assertThat(composite.isSupported()).isTrue();
    }
  }
}
