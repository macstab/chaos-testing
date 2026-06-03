/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack.composers;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.testpack.CompositeChaosGracefulShutdown;

/** L2 composer for {@link CompositeChaosGracefulShutdown}. */
public final class GracefulShutdownComposer implements L2Composer<CompositeChaosGracefulShutdown> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public GracefulShutdownComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosGracefulShutdown annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle =
        adv.slowWait(container, Duration.ofMillis(annotation.drainMs()));
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
  public List<String> describe(final CompositeChaosGracefulShutdown annotation) {
    return List.of(
        "graceful shutdown latency: waitpid() delayed by " + annotation.drainMs() + "ms (slow drain)",
        "drainMs=" + annotation.drainMs(),
        "severity=MODERATE — SIGKILL escalation if drain exceeds orchestrator terminationGracePeriodSeconds");
  }
}
