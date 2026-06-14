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
import com.macstab.chaos.dns.testpack.CompositeChaosShuffledAnswerOrder;

/** L2 composer for {@link CompositeChaosShuffledAnswerOrder}. */
public final class ShuffledAnswerOrderComposer
    implements L2Composer<CompositeChaosShuffledAnswerOrder> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ShuffledAnswerOrderComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosShuffledAnswerOrder annotation) {
    final DnsSelector selector = resolveForwardSelector(annotation.host());
    final RuleHandle handle =
        CompositeDnsChaos.standard().advanced().apply(container, DnsRule.shuffle(selector));
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
  public List<String> describe(final CompositeChaosShuffledAnswerOrder annotation) {
    return List.of(
        "DNS answer-order shuffle: result list randomised on every lookup",
        "host=" + annotation.host(),
        "severity=MILD — exposes address-ordering assumptions and load-balance bugs");
  }

  private static DnsSelector resolveForwardSelector(final String host) {
    if ("*".equals(host)) {
      return DnsSelector.anyForward();
    }
    return DnsSelector.host(host);
  }
}
