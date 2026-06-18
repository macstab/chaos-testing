/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

@DisplayName("RuleRegistry (unit)")
class RuleRegistryTest {

  private RuleRegistry registry;

  @SuppressWarnings("rawtypes")
  private GenericContainer containerA;

  @SuppressWarnings("rawtypes")
  private GenericContainer containerB;

  private TimeRule rule;

  @BeforeEach
  void setUp() {
    registry = new RuleRegistry();
    containerA = mock(GenericContainer.class);
    containerB = mock(GenericContainer.class);
    rule = TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR, 0.1);
  }

  @Test
  void register() {
    final RuleHandle h = new RuleHandle("r1");
    registry.register(containerA, new RuleRegistry.Entry(h, rule));
    assertThat(registry.snapshot(containerA))
        .extracting(RuleRegistry.Entry::handle)
        .containsExactly(h);
  }

  @Test
  void remove() {
    final RuleHandle h = new RuleHandle("r1");
    registry.register(containerA, new RuleRegistry.Entry(h, rule));
    assertThat(registry.remove(containerA, h)).isPresent();
    assertThat(registry.remove(containerA, new RuleHandle("missing"))).isEmpty();
  }

  @Test
  void removeAll() {
    registry.register(containerA, new RuleRegistry.Entry(new RuleHandle("r1"), rule));
    registry.register(containerA, new RuleRegistry.Entry(new RuleHandle("r2"), rule));
    assertThat(registry.removeAll(containerA)).hasSize(2);
    assertThat(registry.snapshot(containerA)).isEmpty();
  }

  @Test
  void isolation() {
    registry.register(containerA, new RuleRegistry.Entry(new RuleHandle("ra"), rule));
    registry.register(containerB, new RuleRegistry.Entry(new RuleHandle("rb"), rule));
    registry.removeAll(containerA);
    assertThat(registry.snapshot(containerA)).isEmpty();
    assertThat(registry.snapshot(containerB)).hasSize(1);
  }

  @Test
  void nulls() {
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
