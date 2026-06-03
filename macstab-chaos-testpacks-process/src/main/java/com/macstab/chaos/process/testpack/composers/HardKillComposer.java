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
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.testpack.CompositeChaosHardKill;

/** L2 composer for {@link CompositeChaosHardKill}. */
public final class HardKillComposer implements L2Composer<CompositeChaosHardKill> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public HardKillComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosHardKill annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle =
        adv.failWait(container, ProcessErrno.ESRCH, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosHardKill annotation) {
    return List.of(
        "hard kill: waitpid() returns ESRCH (child process not found — PID recycled or double-reaped)",
        "toxicity=" + annotation.toxicity(),
        "severity=CRITICAL — supervisors cannot determine job completion; PID registry leaks; operator intervention required");
  }
}
