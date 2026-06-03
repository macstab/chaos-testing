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
import com.macstab.chaos.time.testpack.CompositeChaosTimeTravel;

/**
 * L2 composer for {@link CompositeChaosTimeTravel}.
 *
 * <p>Applies {@code clock_gettime/realtime:OFFSET:-<skewMs>} — a large negative offset on
 * {@code CLOCK_REALTIME} simulating an NTP backward step correction or VM snapshot restore.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TimeTravelComposer implements L2Composer<CompositeChaosTimeTravel> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public TimeTravelComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosTimeTravel annotation) {
    // Negate skewMs — realtime clock must appear in the past.
    final TimeRule rule = TimeRule.offset(TimeClock.REALTIME, Duration.ofMillis(-annotation.skewMs()), annotation.probability());
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
  public List<String> describe(final CompositeChaosTimeTravel annotation) {
    return List.of(
        "CLOCK_REALTIME backward jump (time travel into the past)",
        "skewMs=-" + annotation.skewMs() + " probability=" + annotation.probability(),
        "severity=MODERATE — JWT expiry, Redis TTL, and Raft lease-read assumption failures");
  }
}
