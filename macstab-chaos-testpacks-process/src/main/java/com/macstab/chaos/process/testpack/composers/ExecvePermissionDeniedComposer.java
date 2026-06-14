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
import com.macstab.chaos.process.testpack.CompositeChaosExecvePermissionDenied;

/** L2 composer for {@link CompositeChaosExecvePermissionDenied}. */
public final class ExecvePermissionDeniedComposer
    implements L2Composer<CompositeChaosExecvePermissionDenied> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ExecvePermissionDeniedComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosExecvePermissionDenied annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle = adv.failExecPermission(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosExecvePermissionDenied annotation) {
    return List.of(
        "execve permission denied: execve() returns EACCES (noexec mount or AppArmor/SELinux policy)",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — all exec-based operations fail; shell-outs, scripts, and helpers unavailable");
  }
}
