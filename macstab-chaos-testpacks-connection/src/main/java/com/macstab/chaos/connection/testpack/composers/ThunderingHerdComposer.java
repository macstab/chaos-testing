/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.connection.testpack.CompositeChaosThunderingHerd;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosThunderingHerd}. */
public final class ThunderingHerdComposer implements L2Composer<CompositeChaosThunderingHerd> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ThunderingHerdComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosThunderingHerd annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.errno(
                    endpoint, NetOperation.CONNECT, Errno.ECONNREFUSED, annotation.toxicity()));
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
  public List<String> describe(final CompositeChaosThunderingHerd annotation) {
    return List.of(
        "thundering herd (ECONNREFUSED blackout then simultaneous reconnect storm on rule removal)",
        "toxicity=" + annotation.toxicity(),
        "severity=CRITICAL — reconnect storm on rule removal can cause a second outage; jittered backoff and circuit-breaker coordination required");
  }
}
