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
import com.macstab.chaos.memory.testpack.CompositeChaosMemoryLeak;

/** L2 composer for {@link CompositeChaosMemoryLeak}. */
public final class MemoryLeakComposer implements L2Composer<CompositeChaosMemoryLeak> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public MemoryLeakComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosMemoryLeak annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.failLargeAllocation(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosMemoryLeak annotation) {
    return List.of(
        "Memory leak simulator — very low rate ENOMEM on mmap calls (" + annotation.toxicity() + ")",
        "toxicity=" + annotation.toxicity() + " — designed for long-running soak tests; individual allocations almost always succeed",
        "severity=MODERATE — gradual resource degradation; reveals unchecked ENOMEM in low-frequency code paths");
  }
}
