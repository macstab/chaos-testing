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
import com.macstab.chaos.process.testpack.CompositeChaosExecveMemoryDenied;

/** L2 composer for {@link CompositeChaosExecveMemoryDenied}. */
public final class ExecveMemoryDeniedComposer
    implements L2Composer<CompositeChaosExecveMemoryDenied> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ExecveMemoryDeniedComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosExecveMemoryDenied annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle =
        adv.failExec(container, ProcessErrno.ENOMEM, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosExecveMemoryDenied annotation) {
    return List.of(
        "execve memory denied: execve() returns ENOMEM (OOM during binary image load)",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — exec-ENOMEM is transient but may persist; distinguish from EACCES; retry required");
  }
}
