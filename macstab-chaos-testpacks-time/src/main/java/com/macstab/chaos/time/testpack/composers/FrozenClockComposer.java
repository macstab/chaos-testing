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
import com.macstab.chaos.time.testpack.CompositeChaosFrozenClock;

/**
 * L2 composer for {@link CompositeChaosFrozenClock}.
 *
 * <p>Applies {@code clock_gettime:OFFSET:-<epochMs>} across all clocks using the current epoch
 * in milliseconds as the negative delta. This pins the observed time to approximately the Unix
 * epoch (year 1970), effectively freezing the clock from the application's perspective.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class FrozenClockComposer implements L2Composer<CompositeChaosFrozenClock> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public FrozenClockComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosFrozenClock annotation) {
    // Use the current epoch as a large negative offset across all clock_gettime calls.
    // The offset is fixed at rule-application time; subsequent reads advance only by the tiny
    // real delta since application — which models a clock that appears frozen.
    final long epochMs = System.currentTimeMillis();
    final TimeRule rule = TimeRule.offset(Duration.ofMillis(-epochMs));
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
  public List<String> describe(final CompositeChaosFrozenClock annotation) {
    return List.of(
        "clock_gettime all-clocks OFFSET pinned to Unix epoch (clock appears frozen)",
        "severity=SEVERE — distributed locks, leases, and heartbeat timers stop advancing");
  }
}
