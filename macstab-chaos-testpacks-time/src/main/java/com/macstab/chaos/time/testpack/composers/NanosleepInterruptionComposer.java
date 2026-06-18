/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;
import com.macstab.chaos.time.testpack.CompositeChaosNanosleepInterruption;

/**
 * L2 composer for {@link CompositeChaosNanosleepInterruption}.
 *
 * <p>Applies {@code nanosleep:ERRNO:EINTR@<toxicity>} — probabilistic signal-interruption injection
 * on {@code nanosleep()}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class NanosleepInterruptionComposer
    implements L2Composer<CompositeChaosNanosleepInterruption> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public NanosleepInterruptionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosNanosleepInterruption annotation) {
    final TimeRule rule =
        TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosNanosleepInterruption annotation) {
    return List.of(
        "nanosleep() EINTR injection (signal interrupts sleep)",
        "toxicity=" + annotation.toxicity(),
        "severity=MILD — EINTR retry-loop correctness and busy-wait regression");
  }
}
