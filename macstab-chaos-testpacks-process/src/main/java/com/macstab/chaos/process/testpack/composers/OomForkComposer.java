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
import com.macstab.chaos.process.testpack.CompositeChaosOomFork;

/** L2 composer for {@link CompositeChaosOomFork}. */
public final class OomForkComposer implements L2Composer<CompositeChaosOomFork> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public OomForkComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosOomFork annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle = adv.failFork(container, ProcessErrno.ENOMEM, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosOomFork annotation) {
    return List.of(
        "OOM during fork: fork() returns ENOMEM (kernel cannot allocate process structures)",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — ENOMEM may persist unlike EAGAIN; exponential backoff required; distinguish from transient EAGAIN");
  }
}
