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
import com.macstab.chaos.connection.testpack.CompositeChaosPortAlreadyInUse;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosPortAlreadyInUse}. */
public final class PortAlreadyInUseComposer implements L2Composer<CompositeChaosPortAlreadyInUse> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public PortAlreadyInUseComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosPortAlreadyInUse annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.errno(
                    endpoint,
                    NetOperation.BIND,
                    Errno.EADDRINUSE,
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
  public List<String> describe(final CompositeChaosPortAlreadyInUse annotation) {
    return List.of(
        "port already in use (EADDRINUSE on BIND) — server components fail to open listening sockets on startup",
        "toxicity=" + annotation.toxicity(),
        "severity=MODERATE — service fails to start but does not corrupt data; a restart or TIME_WAIT expiry resolves it");
  }
}
