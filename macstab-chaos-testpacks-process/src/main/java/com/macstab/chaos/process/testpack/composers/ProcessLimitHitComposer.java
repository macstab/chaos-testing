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
import com.macstab.chaos.process.testpack.CompositeChaosProcessLimitHit;

/** L2 composer for {@link CompositeChaosProcessLimitHit}. */
public final class ProcessLimitHitComposer implements L2Composer<CompositeChaosProcessLimitHit> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ProcessLimitHitComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosProcessLimitHit annotation) {
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
  public List<String> describe(final CompositeChaosProcessLimitHit annotation) {
    return List.of(
        "process limit hit: fork() returns EAGAIN (RLIMIT_NPROC / max_threads exhaustion)",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — nine in ten fork attempts fail; workers cannot be spawned; backoff required");
  }
}
