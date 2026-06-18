/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.dns.testpack.CompositeChaosReverseDnsFailure;

/** L2 composer for {@link CompositeChaosReverseDnsFailure}. */
public final class ReverseDnsFailureComposer
    implements L2Composer<CompositeChaosReverseDnsFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ReverseDnsFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosReverseDnsFailure annotation) {
    final RuleHandle handle =
        CompositeDnsChaos.standard()
            .advanced()
            .apply(container, DnsRule.eai(DnsSelector.anyReverse(), EaiErrno.EAI_NONAME));
    return List.of(handle);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle ruleHandle) {
        new LibchaosTransport(LibchaosLib.DNS).removeRules(container, ruleHandle.owner());
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosReverseDnsFailure annotation) {
    return List.of(
        "Reverse DNS failure (EAI_NONAME) on all getnameinfo() calls",
        "severity=MILD — logging degrades; access-control policies relying on PTR records fail");
  }
}
