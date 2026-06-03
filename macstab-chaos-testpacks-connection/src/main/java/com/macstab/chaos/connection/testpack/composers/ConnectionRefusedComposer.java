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
import com.macstab.chaos.connection.testpack.CompositeChaosConnectionRefused;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosConnectionRefused}. */
public final class ConnectionRefusedComposer implements L2Composer<CompositeChaosConnectionRefused> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ConnectionRefusedComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosConnectionRefused annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.errno(
                    endpoint,
                    NetOperation.CONNECT,
                    Errno.ECONNREFUSED,
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
  public List<String> describe(final CompositeChaosConnectionRefused annotation) {
    return List.of(
        "connection refused (ECONNREFUSED on CONNECT) — every TCP connect fails with RST",
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — connection pools cannot warm; circuit breakers must open to prevent retry storms");
  }
}
