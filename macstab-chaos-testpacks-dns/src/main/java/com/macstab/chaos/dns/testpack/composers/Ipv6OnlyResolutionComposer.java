/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.AddressFamily;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.testpack.CompositeChaosIpv6OnlyResolution;

/** L2 composer for {@link CompositeChaosIpv6OnlyResolution}. */
public final class Ipv6OnlyResolutionComposer implements L2Composer<CompositeChaosIpv6OnlyResolution> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public Ipv6OnlyResolutionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosIpv6OnlyResolution annotation) {
    final DnsSelector selector = resolveForwardSelector(annotation.host());
    final RuleHandle handle =
        CompositeDnsChaos.standard()
            .advanced()
            .apply(container, DnsRule.filterFamily(selector, AddressFamily.INET6));
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
  public List<String> describe(final CompositeChaosIpv6OnlyResolution annotation) {
    return List.of(
        "DNS IPv6-only filter: all IPv4 answers stripped from result set",
        "host=" + annotation.host(),
        "severity=MODERATE — tests dual-stack awareness and Happy Eyeballs compliance");
  }

  private static DnsSelector resolveForwardSelector(final String host) {
    if ("*".equals(host)) {
      return DnsSelector.anyForward();
    }
    return DnsSelector.host(host);
  }
}
