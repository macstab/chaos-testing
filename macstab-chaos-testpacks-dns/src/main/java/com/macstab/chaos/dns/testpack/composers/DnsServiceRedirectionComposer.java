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
import com.macstab.chaos.dns.testpack.CompositeChaosDnsServiceRedirection;

/** L2 composer for {@link CompositeChaosDnsServiceRedirection}. */
public final class DnsServiceRedirectionComposer
    implements L2Composer<CompositeChaosDnsServiceRedirection> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public DnsServiceRedirectionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosDnsServiceRedirection annotation) {
    final DnsSelector selector = resolveSelector(annotation.host());
    final RuleHandle handle =
        CompositeDnsChaos.standard()
            .advanced()
            .apply(container, DnsRule.service(selector, annotation.serviceName()));
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
  public List<String> describe(final CompositeChaosDnsServiceRedirection annotation) {
    return List.of(
        "DNS service token rewrite: service → " + annotation.serviceName(),
        "host=" + annotation.host(),
        "severity=SEVERE — wrong port binding; service-registry-aware code fails silently");
  }

  private static DnsSelector resolveSelector(final String host) {
    if ("*".equals(host)) {
      return DnsSelector.anyForward();
    }
    return DnsSelector.host(host);
  }
}
