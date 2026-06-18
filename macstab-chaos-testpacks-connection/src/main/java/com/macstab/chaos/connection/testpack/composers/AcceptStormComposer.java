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
import com.macstab.chaos.connection.testpack.CompositeChaosAcceptStorm;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/** L2 composer for {@link CompositeChaosAcceptStorm}. */
public final class AcceptStormComposer implements L2Composer<CompositeChaosAcceptStorm> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public AcceptStormComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosAcceptStorm annotation) {
    final Endpoint endpoint = EndpointHelper.resolve(annotation.endpoint());
    final RuleHandle handle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.errno(endpoint, NetOperation.ACCEPT, Errno.EMFILE, annotation.toxicity()));
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
  public List<String> describe(final CompositeChaosAcceptStorm annotation) {
    return List.of(
        "Accept storm: accept() → EMFILE at rate " + annotation.toxicity(),
        "severity=SEVERE — inbound connections dropped; spin-loop risk on buggy servers");
  }
}
