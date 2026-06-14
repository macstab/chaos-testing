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
import com.macstab.chaos.dns.testpack.CompositeChaosNxDomain;

/** L2 composer for {@link CompositeChaosNxDomain}. */
public final class NxDomainComposer implements L2Composer<CompositeChaosNxDomain> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public NxDomainComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosNxDomain annotation) {
    final DnsSelector selector = resolveSelector(annotation.host());
    final RuleHandle handle =
        CompositeDnsChaos.standard()
            .advanced()
            .apply(container, DnsRule.eai(selector, EaiErrno.EAI_NONAME));
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
  public List<String> describe(final CompositeChaosNxDomain annotation) {
    return List.of(
        "DNS NXDOMAIN (EAI_NONAME) on all forward lookups",
        "host=" + annotation.host(),
        "severity=SEVERE — complete DNS-based service discovery failure");
  }

  private static DnsSelector resolveSelector(final String host) {
    if ("*".equals(host)) {
      return DnsSelector.anyForward();
    }
    return DnsSelector.host(host);
  }
}
