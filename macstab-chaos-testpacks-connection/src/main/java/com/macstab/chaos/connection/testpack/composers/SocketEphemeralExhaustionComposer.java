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
import com.macstab.chaos.connection.testpack.CompositeChaosSocketEphemeralExhaustion;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosSocketEphemeralExhaustion}. */
public final class SocketEphemeralExhaustionComposer
    implements L2Composer<CompositeChaosSocketEphemeralExhaustion> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SocketEphemeralExhaustionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container,
      final CompositeChaosSocketEphemeralExhaustion annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.errno(
                    endpoint, NetOperation.BIND, Errno.EADDRNOTAVAIL, annotation.toxicity()));
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
  public List<String> describe(final CompositeChaosSocketEphemeralExhaustion annotation) {
    return List.of(
        "Socket ephemeral port exhaustion: bind() → EADDRNOTAVAIL",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — outbound connections fail; pool borrowing cascades");
  }
}
