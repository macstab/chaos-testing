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
import com.macstab.chaos.process.testpack.CompositeChaosForkBomb;

/** L2 composer for {@link CompositeChaosForkBomb}. */
public final class ForkBombComposer implements L2Composer<CompositeChaosForkBomb> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ForkBombComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosForkBomb annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle = adv.failFork(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosForkBomb annotation) {
    return List.of(
        "fork bomb saturation: fork() returns EAGAIN at very high rate (post-saturation steady state)",
        "toxicity=" + annotation.toxicity(),
        "severity=CRITICAL — virtually all process creation fails; data loss possible; node intervention required");
  }
}
