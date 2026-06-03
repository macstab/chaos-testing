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
import com.macstab.chaos.process.testpack.CompositeChaosThreadPoolExhaustion;

/** L2 composer for {@link CompositeChaosThreadPoolExhaustion}. */
public final class ThreadPoolExhaustionComposer
    implements L2Composer<CompositeChaosThreadPoolExhaustion> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ThreadPoolExhaustionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosThreadPoolExhaustion annotation) {
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    final RuleHandle handle = adv.failThreadCreation(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosThreadPoolExhaustion annotation) {
    return List.of(
        "thread pool exhaustion: pthread_create() returns EAGAIN (OS thread-count limit hit)",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — executor services reject tasks; reactive workers lost; circuit breaker required");
  }
}
