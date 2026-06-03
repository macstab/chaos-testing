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
import com.macstab.chaos.process.testpack.CompositeChaosSpawnFailure;

/** L2 composer for {@link CompositeChaosSpawnFailure}. */
public final class SpawnFailureComposer implements L2Composer<CompositeChaosSpawnFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SpawnFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosSpawnFailure annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle =
        adv.failSpawn(container, ProcessErrno.ENOMEM, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosSpawnFailure annotation) {
    return List.of(
        "spawn failure: posix_spawn() returns ENOMEM (memory-constrained subprocess launch)",
        "toxicity=" + annotation.toxicity(),
        "severity=MODERATE — half of spawn attempts fail; inconsistent subprocess execution; leak risk");
  }
}
