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
import com.macstab.chaos.dns.testpack.CompositeChaosDnsCachePoisoning;

/** L2 composer for {@link CompositeChaosDnsCachePoisoning}. */
public final class DnsCachePoisoningComposer
    implements L2Composer<CompositeChaosDnsCachePoisoning> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public DnsCachePoisoningComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosDnsCachePoisoning annotation) {
    final DnsSelector selector = resolveSelector(annotation.host());
    final RuleHandle handle =
        CompositeDnsChaos.standard()
            .advanced()
            .apply(container, DnsRule.rewrite(selector, annotation.redirectTo()));
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
  public List<String> describe(final CompositeChaosDnsCachePoisoning annotation) {
    return List.of(
        "DNS cache poisoning: all lookups rewritten to " + annotation.redirectTo(),
        "host=" + annotation.host(),
        "severity=CRITICAL — silent traffic misdirection; exfiltration risk");
  }

  private static DnsSelector resolveSelector(final String host) {
    if ("*".equals(host)) {
      return DnsSelector.anyForward();
    }
    return DnsSelector.host(host);
  }
}
