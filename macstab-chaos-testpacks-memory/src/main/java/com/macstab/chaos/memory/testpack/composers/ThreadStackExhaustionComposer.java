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
import com.macstab.chaos.memory.testpack.CompositeChaosThreadStackExhaustion;

/** L2 composer for {@link CompositeChaosThreadStackExhaustion}. */
public final class ThreadStackExhaustionComposer
    implements L2Composer<CompositeChaosThreadStackExhaustion> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ThreadStackExhaustionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosThreadStackExhaustion annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.failThreadCreation(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosThreadStackExhaustion annotation) {
    return List.of(
        "Thread stack exhaustion — ENOMEM on anonymous mmap at rate " + annotation.toxicity()
            + " (pthread_create stack allocation fails)",
        "toxicity=" + annotation.toxicity() + " — simulates process thread-limit or address-space fragmentation",
        "severity=SEVERE — thread pool degrades silently; service stops accepting work if workers are not replaced");
  }
}
