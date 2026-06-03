/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack.composers;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.connection.testpack.CompositeChaosPollTimeout;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosPollTimeout}. */
public final class PollTimeoutComposer implements L2Composer<CompositeChaosPollTimeout> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public PollTimeoutComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosPollTimeout annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.timeout(
                    endpoint,
                    Duration.ofMillis(annotation.timeoutMs()),
                    annotation.toxicity()));
    return List.of(handle);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle rh) {
        new LibchaosTransport(LibchaosLib.NET).removeRules(container, rh.owner());
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosPollTimeout annotation) {
    return List.of(
        "Poll timeout injection: poll()/epoll_wait() forces timeout after " + annotation.timeoutMs() + "ms",
        "toxicity=" + annotation.toxicity(),
        "severity=MODERATE — tests event-loop spurious timeout handling");
  }
}
