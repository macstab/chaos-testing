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
import com.macstab.chaos.memory.testpack.CompositeChaosHugepageFailure;

/** L2 composer for {@link CompositeChaosHugepageFailure}. */
public final class HugepageFailureComposer implements L2Composer<CompositeChaosHugepageFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public HugepageFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosHugepageFailure annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.failPagePurge(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosHugepageFailure annotation) {
    return List.of(
        "Hugepage failure — ENOMEM on madvise at rate "
            + annotation.toxicity()
            + " (MADV_HUGEPAGE / MADV_DONTNEED advisory cannot be satisfied)",
        "toxicity="
            + annotation.toxicity()
            + " — simulates memory fragmentation or kernel THP policy; allocator must fall back to base pages",
        "severity=MILD — madvise is always a hint; well-written allocators silently continue with 4 KiB pages");
  }
}
