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
import com.macstab.chaos.connection.testpack.CompositeChaosUnreachableHost;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosUnreachableHost}. */
public final class UnreachableHostComposer implements L2Composer<CompositeChaosUnreachableHost> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public UnreachableHostComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosUnreachableHost annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.errno(
                    endpoint,
                    NetOperation.CONNECT,
                    Errno.EHOSTUNREACH,
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
  public List<String> describe(final CompositeChaosUnreachableHost annotation) {
    return List.of(
        "unreachable host (EHOSTUNREACH on CONNECT) — routing layer fails before any packet is sent",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — failure is at the IP routing layer; restarting the target service does not help");
  }
}
