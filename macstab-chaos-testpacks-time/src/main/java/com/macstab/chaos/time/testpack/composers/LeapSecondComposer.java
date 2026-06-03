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
import com.macstab.chaos.time.testpack.CompositeChaosLeapSecond;

/**
 * L2 composer for {@link CompositeChaosLeapSecond}.
 *
 * <p>Applies {@code clock_gettime/realtime:OFFSET:+1000} — a canonical +1 second offset on
 * {@code CLOCK_REALTIME} simulating IERS leap-second insertion.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class LeapSecondComposer implements L2Composer<CompositeChaosLeapSecond> {

  private static final Duration LEAP_SECOND = Duration.ofMillis(1_000L);

  /** Public no-arg constructor required by the L2 composer contract. */
  public LeapSecondComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosLeapSecond annotation) {
    final TimeRule rule = TimeRule.offset(TimeClock.REALTIME, LEAP_SECOND, annotation.probability());
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
  public List<String> describe(final CompositeChaosLeapSecond annotation) {
    return List.of(
        "CLOCK_REALTIME +1000 ms leap-second simulation (IERS insertion) probability=" + annotation.probability(),
        "severity=MODERATE — JVM hrtimer and NTP-slew assumption failures");
  }
}
