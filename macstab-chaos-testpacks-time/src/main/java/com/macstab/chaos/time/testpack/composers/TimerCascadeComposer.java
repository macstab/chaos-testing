/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.testpack.composers;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;
import com.macstab.chaos.time.testpack.CompositeChaosTimerCascade;

/**
 * L2 composer for {@link CompositeChaosTimerCascade}.
 *
 * <p>Applies {@code nanosleep:LATENCY:<latencyMs>} — extra latency added to every
 * {@code nanosleep()} call simulating a CPU-overloaded scheduler that fires timers late.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TimerCascadeComposer implements L2Composer<CompositeChaosTimerCascade> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public TimerCascadeComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosTimerCascade annotation) {
    final TimeRule rule = TimeRule.latency(TimeSelector.NANOSLEEP, Duration.ofMillis(annotation.latencyMs()));
    final AdvancedTimeChaos adv = CompositeTimeChaos.standard().advanced();
    final RuleHandle handle = adv.apply(container, rule);
    return List.of(handle);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle ruleHandle) {
        new LibchaosTransport(LibchaosLib.TIME).removeRules(container, ruleHandle.owner());
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosTimerCascade annotation) {
    return List.of(
        "nanosleep() extra latency (timer-cascade / CPU-starvation simulation)",
        "latencyMs=" + annotation.latencyMs(),
        "severity=MODERATE — cascading deadline miss and retry-budget exhaustion");
  }
}
