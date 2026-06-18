/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process;

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
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.model.Signal;
import com.macstab.chaos.core.spi.ProcessChaosStrategy;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;

@DisplayName("CompositeProcessChaos (unit)")
class CompositeProcessChaosUnitTest {

  private GenericContainer<?> container;
  private ProcessChaosStrategy strategyA;
  private ProcessChaosStrategy strategyB;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    strategyA = mock(ProcessChaosStrategy.class);
    strategyB = mock(ProcessChaosStrategy.class);
    when(strategyA.supports(any())).thenReturn(true);
    when(strategyB.supports(any())).thenReturn(true);
  }

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {
    @Test
    void rejectsNullEmpty() {
      assertThatThrownBy(() -> new CompositeProcessChaos(null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new CompositeProcessChaos(Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class);
      final java.util.ArrayList<ProcessChaosStrategy> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> new CompositeProcessChaos(withNull))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("capability fall-through (mutating verbs)")
  class FallThrough {
    @Test
    void firstHandlesKill() {
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      composite.kill(container, "nginx", Signal.SIGTERM);
      verify(strategyA).kill(container, "nginx", Signal.SIGTERM);
      verify(strategyB, never()).kill(any(), any(), any());
    }

    @Test
    void fallsThroughOnUnsupported() {
      doThrow(new ChaosUnsupportedOperationException("nope"))
          .when(strategyA)
          .kill(any(), any(), any());
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      composite.kill(container, "nginx", Signal.SIGTERM);
      verify(strategyB).kill(container, "nginx", Signal.SIGTERM);
    }

    @Test
    void noApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.kill(container, "x", Signal.SIGTERM))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    void pauseLimitRoute() {
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      composite.pause(container, "nginx", Duration.ofSeconds(1));
      composite.limitProcesses(container, 100);
      verify(strategyA).pause(container, "nginx", Duration.ofSeconds(1));
      verify(strategyA).limitProcesses(container, 100);
    }
  }

  @Nested
  @DisplayName("reading verbs (listProcesses)")
  class Reading {
    @Test
    void returnsFirst() {
      when(strategyA.listProcesses(container)).thenReturn(List.of());
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      assertThat(composite.listProcesses(container)).isEmpty();
      verify(strategyB, never()).listProcesses(any());
    }

    @Test
    void fallsThrough() {
      when(strategyA.listProcesses(any()))
          .thenThrow(new ChaosUnsupportedOperationException("libchaos doesn't enumerate"));
      when(strategyB.listProcesses(container)).thenReturn(List.of());
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      assertThat(composite.listProcesses(container)).isEmpty();
      verify(strategyB).listProcesses(container);
    }
  }

  @Nested
  @DisplayName("reset — fan-out, best-effort")
  class ResetFanOut {
    @Test
    void fansOut() {
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyA).reset(container);
      verify(strategyB).reset(container);
    }

    @Test
    void aggregateOnAllFail() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      doThrow(new ChaosOperationFailedException("b")).when(strategyB).reset(any());
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.reset(container))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    void partialFailure() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyB).reset(container);
    }
  }

  @Nested
  @DisplayName("advanced() accessor")
  class Advanced {
    @Test
    void returnsAdvanced() {
      final AdvancedProcessChaos adv =
          mock(
              AdvancedProcessChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(ProcessChaosStrategy.class));
      when(((ProcessChaosStrategy) adv).supports(any())).thenReturn(true);
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of((ProcessChaosStrategy) adv, strategyB));
      assertThat(composite.advanced()).isSameAs(adv);
    }

    @Test
    void noAdvancedRegistered() {
      final CompositeProcessChaos composite = new CompositeProcessChaos(List.of(strategyA));
      assertThatThrownBy(composite::advanced)
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("AdvancedProcessChaos");
    }

    @Test
    void applyViaAccessor() {
      final AdvancedProcessChaos adv =
          mock(
              AdvancedProcessChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(ProcessChaosStrategy.class));
      when(((ProcessChaosStrategy) adv).supports(any())).thenReturn(true);
      final RuleHandle expected = new RuleHandle("r1");
      final ProcessRule rule =
          ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN, 0.5);
      when(adv.apply(container, rule)).thenReturn(expected);
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of((ProcessChaosStrategy) adv, strategyB));
      assertThat(composite.advanced().apply(container, rule)).isSameAs(expected);
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {
    @Test
    void installToolsFansOut() {
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      composite.installTools(container);
      verify(strategyA).installTools(container);
      verify(strategyB).installTools(container);
    }

    @Test
    void isSupportedAny() {
      when(strategyA.isSupported()).thenReturn(false);
      when(strategyB.isSupported()).thenReturn(true);
      final CompositeProcessChaos composite =
          new CompositeProcessChaos(List.of(strategyA, strategyB));
      assertThat(composite.isSupported()).isTrue();
    }
  }
}
