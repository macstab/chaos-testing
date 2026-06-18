/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time;

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
import com.macstab.chaos.core.spi.TimeChaosStrategy;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

@DisplayName("CompositeTimeChaos (unit)")
class CompositeTimeChaosUnitTest {

  private GenericContainer<?> container;
  private TimeChaosStrategy strategyA;
  private TimeChaosStrategy strategyB;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    strategyA = mock(TimeChaosStrategy.class);
    strategyB = mock(TimeChaosStrategy.class);
    when(strategyA.supports(any())).thenReturn(true);
    when(strategyB.supports(any())).thenReturn(true);
  }

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {
    @Test
    void rejectsNullEmpty() {
      assertThatThrownBy(() -> new CompositeTimeChaos((List<TimeChaosStrategy>) null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new CompositeTimeChaos(Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class);
      final java.util.ArrayList<TimeChaosStrategy> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> new CompositeTimeChaos(withNull))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("capability fall-through (mutating verbs)")
  class FallThrough {
    @Test
    void firstHandlesShift() {
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      composite.shift(container, Duration.ofSeconds(60));
      verify(strategyA).shift(container, Duration.ofSeconds(60));
      verify(strategyB, never()).shift(any(), any());
    }

    @Test
    void fallsThroughOnUnsupported() {
      doThrow(new ChaosUnsupportedOperationException("nope")).when(strategyA).shift(any(), any());
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      composite.shift(container, Duration.ofSeconds(60));
      verify(strategyB).shift(container, Duration.ofSeconds(60));
    }

    @Test
    void noApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.shift(container, Duration.ofSeconds(60)))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    void driftRoutes() {
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      composite.drift(container, 2.0);
      verify(strategyA).drift(container, 2.0);
    }
  }

  @Nested
  @DisplayName("reset — fan-out, best-effort")
  class ResetFanOut {
    @Test
    void fansOut() {
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyA).reset(container);
      verify(strategyB).reset(container);
    }

    @Test
    void aggregateOnAllFail() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      doThrow(new ChaosOperationFailedException("b")).when(strategyB).reset(any());
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.reset(container))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    void partialFailure() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyB).reset(container);
    }
  }

  @Nested
  @DisplayName("advanced() accessor")
  class Advanced {
    @Test
    void returnsAdvanced() {
      final AdvancedTimeChaos adv =
          mock(
              AdvancedTimeChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(TimeChaosStrategy.class));
      when(((TimeChaosStrategy) adv).supports(any())).thenReturn(true);
      final CompositeTimeChaos composite =
          new CompositeTimeChaos(List.of((TimeChaosStrategy) adv, strategyB));
      assertThat(composite.advanced()).isSameAs(adv);
    }

    @Test
    void noAdvancedRegistered() {
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA));
      assertThatThrownBy(composite::advanced)
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("AdvancedTimeChaos");
    }

    @Test
    void applyViaAccessor() {
      final AdvancedTimeChaos adv =
          mock(
              AdvancedTimeChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(TimeChaosStrategy.class));
      when(((TimeChaosStrategy) adv).supports(any())).thenReturn(true);
      final RuleHandle expected = new RuleHandle("r1");
      final TimeRule rule = TimeRule.errno(TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL, 0.5);
      when(adv.apply(container, rule)).thenReturn(expected);
      final CompositeTimeChaos composite =
          new CompositeTimeChaos(List.of((TimeChaosStrategy) adv, strategyB));
      assertThat(composite.advanced().apply(container, rule)).isSameAs(expected);
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {
    @Test
    void installToolsFansOut() {
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      composite.installTools(container);
      verify(strategyA).installTools(container);
      verify(strategyB).installTools(container);
    }

    @Test
    void isSupportedAny() {
      when(strategyA.isSupported()).thenReturn(false);
      when(strategyB.isSupported()).thenReturn(true);
      final CompositeTimeChaos composite = new CompositeTimeChaos(List.of(strategyA, strategyB));
      assertThat(composite.isSupported()).isTrue();
    }
  }
}
