/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.AdvancedConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.connection.testpack.CompositeChaosSlowDownstream;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosSlowDownstream}. */
public final class SlowDownstreamComposer implements L2Composer<CompositeChaosSlowDownstream> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SlowDownstreamComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosSlowDownstream annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final AdvancedConnectionChaos adv = CompositeConnectionChaos.standard().advanced();
    final List<Object> handles = new ArrayList<>();
    handles.add(
        adv.apply(
            container,
            NetRule.latency(
                endpoint, NetOperation.CONNECT, Duration.ofMillis(annotation.latencyMs()), 1.0)));
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                endpoint, NetOperation.SEND, Errno.EPIPE, annotation.sendFailToxicity())));
    return handles;
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
  public List<String> describe(final CompositeChaosSlowDownstream annotation) {
    return List.of(
        "Slow downstream: connect() +latency "
            + annotation.latencyMs()
            + "ms + send() → EPIPE at "
            + annotation.sendFailToxicity(),
        "severity=MODERATE — tests connection-timeout config and write-error resilience");
  }
}
