/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack.composers;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.testpack.CompositeChaosThreadCreateSlow;

/** L2 composer for {@link CompositeChaosThreadCreateSlow}. */
public final class ThreadCreateSlowComposer implements L2Composer<CompositeChaosThreadCreateSlow> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ThreadCreateSlowComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosThreadCreateSlow annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle =
        adv.slowThreadCreation(container, Duration.ofMillis(annotation.latencyMs()));
    return List.of(handle);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle rh) {
        new LibchaosTransport(LibchaosLib.PROCESS).removeRules(container, rh.owner());
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosThreadCreateSlow annotation) {
    return List.of(
        "slow thread creation: pthread_create() delayed by " + annotation.latencyMs() + "ms",
        "latencyMs=" + annotation.latencyMs(),
        "severity=MODERATE — request latency spikes during pool expansion; startup warm-up mitigates impact");
  }
}
