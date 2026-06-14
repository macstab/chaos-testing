/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.testpack.CompositeChaosOomKill;

/** L2 composer for {@link CompositeChaosOomKill}. */
public final class OomKillComposer implements L2Composer<CompositeChaosOomKill> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public OomKillComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosOomKill annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.simulateOomKiller(container, annotation.toxicity());
    return List.of(handle);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle ruleHandle) {
        new LibchaosTransport(LibchaosLib.MEMORY).removeRules(container, ruleHandle.owner());
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosOomKill annotation) {
    return List.of(
        "OOM-kill simulator — ENOMEM at 100% on every interposed VM syscall (mmap/mprotect/madvise)",
        "toxicity="
            + annotation.toxicity()
            + " — ENOMEM on every interposed VM syscall at this rate",
        "severity=CRITICAL — service cannot allocate memory, create threads, or JIT-compile; restart required");
  }
}
