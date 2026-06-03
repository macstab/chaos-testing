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
import com.macstab.chaos.memory.testpack.CompositeChaosMemoryPressure;

/** L2 composer for {@link CompositeChaosMemoryPressure}. */
public final class MemoryPressureComposer implements L2Composer<CompositeChaosMemoryPressure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public MemoryPressureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosMemoryPressure annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.simulateMemoryPressure(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosMemoryPressure annotation) {
    return List.of(
        "Memory pressure — intermittent ENOMEM on mmap calls at rate " + annotation.toxicity(),
        "toxicity=" + annotation.toxicity() + " — simulates noisy-neighbour cgroup pressure or memory fragmentation",
        "severity=MODERATE — service drops some allocations but should recover if ENOMEM is handled");
  }
}
