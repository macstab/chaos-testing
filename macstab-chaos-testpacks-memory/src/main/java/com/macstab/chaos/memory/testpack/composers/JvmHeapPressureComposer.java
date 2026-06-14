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
import com.macstab.chaos.memory.testpack.CompositeChaosJvmHeapPressure;

/** L2 composer for {@link CompositeChaosJvmHeapPressure}. */
public final class JvmHeapPressureComposer implements L2Composer<CompositeChaosJvmHeapPressure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public JvmHeapPressureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosJvmHeapPressure annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.failHeapAllocation(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosJvmHeapPressure annotation) {
    return List.of(
        "JVM heap pressure — ENOMEM on anonymous mmap at rate "
            + annotation.toxicity()
            + " (JVM heap expansion call fails)",
        "toxicity="
            + annotation.toxicity()
            + " — simulates cgroup memory.max limit or -Xmx exhaustion; triggers GC pressure and OutOfMemoryError",
        "severity=MODERATE — JVM remains functional under load; individual large allocations may throw OutOfMemoryError");
  }
}
