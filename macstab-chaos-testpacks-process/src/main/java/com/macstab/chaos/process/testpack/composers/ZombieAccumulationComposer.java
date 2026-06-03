/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.testpack.CompositeChaosZombieAccumulation;

/** L2 composer for {@link CompositeChaosZombieAccumulation}. */
public final class ZombieAccumulationComposer
    implements L2Composer<CompositeChaosZombieAccumulation> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ZombieAccumulationComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosZombieAccumulation annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle = adv.phantomWait(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosZombieAccumulation annotation) {
    return List.of(
        "zombie accumulation: waitpid() returns ECHILD (no children — un-reaped zombie build-up)",
        "toxicity=" + annotation.toxicity(),
        "severity=MODERATE — zombies accumulate until process table fills; init process required");
  }
}
