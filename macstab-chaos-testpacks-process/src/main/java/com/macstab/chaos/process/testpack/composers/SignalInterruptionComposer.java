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
import com.macstab.chaos.process.testpack.CompositeChaosSignalInterruption;

/** L2 composer for {@link CompositeChaosSignalInterruption}. */
public final class SignalInterruptionComposer
    implements L2Composer<CompositeChaosSignalInterruption> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SignalInterruptionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosSignalInterruption annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle = adv.signalInterruptWait(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosSignalInterruption annotation) {
    return List.of(
        "signal interruption: waitpid() returns EINTR (bypasses SA_RESTART — exercises retry loop)",
        "toxicity=" + annotation.toxicity(),
        "severity=MILD — transparent with correct EINTR retry; latent bug surface if retry is missing");
  }
}
