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
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.testpack.CompositeChaosSlowMonotonic;

/**
 * L2 composer for {@link CompositeChaosSlowMonotonic}.
 *
 * <p>Applies {@code clock_gettime/monotonic:OFFSET:-<skewMs>} — a negative offset on
 * {@code CLOCK_MONOTONIC} to make the monotonic clock appear slower than wall time.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SlowMonotonicComposer implements L2Composer<CompositeChaosSlowMonotonic> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SlowMonotonicComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosSlowMonotonic annotation) {
    // Negate skewMs — monotonic clock must appear behind (slower than) real elapsed time.
    final TimeRule rule = TimeRule.offset(TimeClock.MONOTONIC, Duration.ofMillis(-annotation.skewMs()), annotation.probability());
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
  public List<String> describe(final CompositeChaosSlowMonotonic annotation) {
    return List.of(
        "CLOCK_MONOTONIC negative offset (monotonic clock runs slower than wall time)",
        "skewMs=-" + annotation.skewMs() + " probability=" + annotation.probability(),
        "severity=MILD — election timeout and nanoTime() assumption failures");
  }
}
