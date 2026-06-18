/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory;

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
import com.macstab.chaos.core.spi.MemoryChaosStrategy;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

@DisplayName("CompositeMemoryChaos (unit)")
class CompositeMemoryChaosUnitTest {

  private GenericContainer<?> container;
  private MemoryChaosStrategy strategyA;
  private MemoryChaosStrategy strategyB;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    strategyA = mock(MemoryChaosStrategy.class);
    strategyB = mock(MemoryChaosStrategy.class);
    when(strategyA.supports(any())).thenReturn(true);
    when(strategyB.supports(any())).thenReturn(true);
  }

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {
    @Test
    void rejectsNullEmpty() {
      assertThatThrownBy(() -> new CompositeMemoryChaos(null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new CompositeMemoryChaos(Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class);
      final java.util.ArrayList<MemoryChaosStrategy> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> new CompositeMemoryChaos(withNull))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("capability fall-through (mutating verbs)")
  class FallThrough {
    @Test
    @DisplayName("first applicable handles setLimit; later strategy untouched")
    void first() {
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      composite.setLimit(container, "512M");
      verify(strategyA).setLimit(container, "512M");
      verify(strategyB, never()).setLimit(any(), any());
    }

    @Test
    @DisplayName("ChaosUnsupportedOperationException falls through to next strategy")
    void unsupported() {
      doThrow(new ChaosUnsupportedOperationException("nope"))
          .when(strategyA)
          .setLimit(any(), any());
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      composite.setLimit(container, "512M");
      verify(strategyB).setLimit(container, "512M");
    }

    @Test
    @DisplayName("no applicable strategy → ChaosOperationFailedException")
    void noApplicable() {
      when(strategyA.supports(any())).thenReturn(false);
      when(strategyB.supports(any())).thenReturn(false);
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.setLimit(container, "512M"))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    @DisplayName("setPressure / stress all use the same routing")
    void otherMutating() {
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      composite.setPressure(container, "400M");
      composite.stress(container, "300M");
      verify(strategyA).setPressure(container, "400M");
      verify(strategyA).stress(container, "300M");
    }
  }

  @Nested
  @DisplayName("reading verbs (getCurrentUsage / getPressure)")
  class Reading {
    @Test
    @DisplayName("first applicable's value is returned")
    void returnsFirst() {
      when(strategyA.getCurrentUsage(container)).thenReturn(123456L);
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      assertThat(composite.getCurrentUsage(container)).isEqualTo(123456L);
      verify(strategyB, never()).getCurrentUsage(any());
    }

    @Test
    @DisplayName("ChaosUnsupportedOperationException falls through to next applicable")
    void readingFallThrough() {
      when(strategyA.getCurrentUsage(any()))
          .thenThrow(new ChaosUnsupportedOperationException("libchaos can't read PSI"));
      when(strategyB.getCurrentUsage(container)).thenReturn(999L);
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      assertThat(composite.getCurrentUsage(container)).isEqualTo(999L);
    }
  }

  @Nested
  @DisplayName("reset — fan-out, best-effort")
  class ResetFanOut {
    @Test
    void fansOut() {
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyA).reset(container);
      verify(strategyB).reset(container);
    }

    @Test
    void aggregateWhenAllFail() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      doThrow(new ChaosOperationFailedException("b")).when(strategyB).reset(any());
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      assertThatThrownBy(() -> composite.reset(container))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    void partialFailureDoesNotThrow() {
      doThrow(new ChaosOperationFailedException("a")).when(strategyA).reset(any());
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      composite.reset(container);
      verify(strategyB).reset(container);
    }
  }

  @Nested
  @DisplayName("advanced() accessor")
  class Advanced {
    @Test
    void returnsAdvanced() {
      final AdvancedMemoryChaos adv =
          mock(
              AdvancedMemoryChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(MemoryChaosStrategy.class));
      when(((MemoryChaosStrategy) adv).supports(any())).thenReturn(true);
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of((MemoryChaosStrategy) adv, strategyB));
      assertThat(composite.advanced()).isSameAs(adv);
    }

    @Test
    void noAdvancedRegistered() {
      final CompositeMemoryChaos composite = new CompositeMemoryChaos(List.of(strategyA));
      assertThatThrownBy(composite::advanced)
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("AdvancedMemoryChaos");
    }

    @Test
    void applyViaAccessor() {
      final AdvancedMemoryChaos adv =
          mock(
              AdvancedMemoryChaos.class,
              org.mockito.Mockito.withSettings().extraInterfaces(MemoryChaosStrategy.class));
      when(((MemoryChaosStrategy) adv).supports(any())).thenReturn(true);
      final RuleHandle expected = new RuleHandle("r1");
      final MemoryRule rule = MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, 0.5);
      when(adv.apply(container, rule)).thenReturn(expected);
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of((MemoryChaosStrategy) adv, strategyB));
      assertThat(composite.advanced().apply(container, rule)).isSameAs(expected);
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {
    @Test
    void installToolsFansOut() {
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      composite.installTools(container);
      verify(strategyA).installTools(container);
      verify(strategyB).installTools(container);
    }

    @Test
    void installToolsTolerant() {
      doThrow(new RuntimeException("a")).when(strategyA).installTools(any());
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      composite.installTools(container);
      verify(strategyB).installTools(container);
    }

    @Test
    void isSupportedAny() {
      when(strategyA.isSupported()).thenReturn(false);
      when(strategyB.isSupported()).thenReturn(true);
      final CompositeMemoryChaos composite =
          new CompositeMemoryChaos(List.of(strategyA, strategyB));
      assertThat(composite.isSupported()).isTrue();
    }
  }
}
