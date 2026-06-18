/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuleSwapper apply/remove adapter")
class RuleSwapperTest {

  @Test
  @DisplayName("first sample applies, does not remove")
  void firstSampleNoRemove() throws Exception {
    final AtomicInteger nextHandle = new AtomicInteger();
    final List<Integer> applied = new ArrayList<>();
    final List<Integer> removed = new ArrayList<>();

    final ValueConsumer<Double> consumer =
        RuleSwapper.swap(
            v -> {
              final int handle = nextHandle.incrementAndGet();
              applied.add(handle);
              return handle;
            },
            removed::add);

    consumer.accept(0.1);

    assertThat(applied).containsExactly(1);
    assertThat(removed).isEmpty();
  }

  @Test
  @DisplayName("each subsequent sample removes the previously-applied handle exactly once")
  void subsequentSamplesSwap() throws Exception {
    final AtomicInteger nextHandle = new AtomicInteger();
    final List<Integer> applied = new ArrayList<>();
    final List<Integer> removed = new ArrayList<>();

    final ValueConsumer<Double> consumer =
        RuleSwapper.swap(
            v -> {
              final int handle = nextHandle.incrementAndGet();
              applied.add(handle);
              return handle;
            },
            removed::add);

    consumer.accept(0.1);
    consumer.accept(0.2);
    consumer.accept(0.3);

    assertThat(applied).containsExactly(1, 2, 3);
    assertThat(removed).containsExactly(1, 2);
  }

  @Test
  @DisplayName("apply runs before remove — last handle is the active one after swap")
  void applyBeforeRemove() throws Exception {
    final List<String> events = new ArrayList<>();
    final ValueConsumer<Integer> consumer =
        RuleSwapper.swap(
            v -> {
              events.add("apply:" + v);
              return v;
            },
            h -> events.add("remove:" + h));

    consumer.accept(1);
    consumer.accept(2);

    // ordering: first sample applies. second sample applies BEFORE removing the first.
    assertThat(events).containsExactly("apply:1", "apply:2", "remove:1");
  }

  @Test
  @DisplayName("null apply / null remove are rejected at construction")
  void nullArgs() {
    assertThatThrownBy(() -> RuleSwapper.swap(null, h -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RuleSwapper.swap(v -> v, null))
        .isInstanceOf(NullPointerException.class);
  }
}
