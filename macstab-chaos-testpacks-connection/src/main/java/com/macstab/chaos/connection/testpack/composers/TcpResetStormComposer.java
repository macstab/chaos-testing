/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.connection.testpack.CompositeChaosTcpResetStorm;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosTcpResetStorm}. */
public final class TcpResetStormComposer implements L2Composer<CompositeChaosTcpResetStorm> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public TcpResetStormComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosTcpResetStorm annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.corrupt(endpoint, annotation.rate(), 1.0));
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
  public List<String> describe(final CompositeChaosTcpResetStorm annotation) {
    return List.of(
        "TCP reset storm (CORRUPT on RECV) — inbound data corrupted at rate=" + annotation.rate() + ", causing connection resets",
        "rate=" + annotation.rate(),
        "severity=SEVERE — connection-reuse pools are disproportionately affected; RST storm follows corrupted frame detection");
  }
}
