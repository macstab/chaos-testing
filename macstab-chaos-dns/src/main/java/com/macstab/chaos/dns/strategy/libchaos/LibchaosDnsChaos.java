/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.strategy.libchaos;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.spi.DnsChaosStrategy;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.dns.api.AdvancedDnsChaos;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.AddressFamily;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolver-boundary DNS chaos strategy backed by {@code libchaos-dns} via {@link
 * LibchaosTransport}.
 *
 * <p>Implements both {@link DnsChaosStrategy} (so it composes with the iptables strategy) and
 * {@link AdvancedDnsChaos} (so it surfaces libchaos-dns' full capability set). Pre-flight
 * preparation must happen before {@code container.start()} — see {@link LibchaosTransport#prepare};
 * this strategy does not start anything itself.
 *
 * <p><strong>Portable verbs.</strong> Unlike the connection and filesystem strategies, libchaos-dns
 * handles both portable verbs natively: {@code blockResolution} maps to an {@code EAI_FAIL} rule on
 * the host's forward selector, and {@code delayResolution} maps to a wildcard {@code LATENCY} rule.
 * There is no need to throw {@link
 * com.macstab.chaos.core.exception.ChaosUnsupportedOperationException} — the composite routes by
 * {@code supports()} alone.
 *
 * <p><strong>Thread-safety:</strong> safe for concurrent use across containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class LibchaosDnsChaos implements AdvancedDnsChaos, DnsChaosStrategy {

  private final LibchaosTransport transport;
  private final RuleRegistry registry;
  private final AtomicLong ownerCounter;

  /** Default constructor — uses {@code LibchaosLib.DNS}. */
  public LibchaosDnsChaos() {
    this(new LibchaosTransport(LibchaosLib.DNS));
  }

  /**
   * Package-private for testing.
   *
   * @param transport pre-configured transport (typically a mock)
   */
  LibchaosDnsChaos(final LibchaosTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.registry = new RuleRegistry();
    this.ownerCounter = new AtomicLong(0L);
  }

  // ==================== ChaosProvider ====================

  @Override
  public boolean isSupported() {
    return true;
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    // No-op: libchaos-dns is installed pre-start via LibchaosTransport.prepare().
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      return;
    }
    removeAll(container);
  }

  // ==================== DnsChaosStrategy ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    try {
      return transport.isActive(container);
    } catch (final RuntimeException ex) {
      log.debug("isActive probe failed; reporting unsupported", ex);
      return false;
    }
  }

  // ==================== DnsChaos (portable verbs) ====================

  @Override
  public void blockResolution(final GenericContainer<?> container, final String hostname) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(hostname, "hostname must not be null");
    requirePrepared(container);
    applySingle(container, DnsRule.eai(DnsSelector.host(hostname), EaiErrno.EAI_FAIL));
  }

  @Override
  public void delayResolution(final GenericContainer<?> container, final Duration delay) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(delay, "delay must not be null");
    requirePrepared(container);
    applySingle(container, DnsRule.latency(DnsSelector.anyForward(), delay));
  }

  // ==================== AdvancedDnsChaos: generic ====================

  @Override
  public RuleHandle apply(final GenericContainer<?> container, final DnsRule rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    requirePrepared(container);
    return applySingle(container, rule);
  }

  @Override
  public List<RuleHandle> applyAll(final GenericContainer<?> container, final List<DnsRule> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    rules.forEach(r -> Objects.requireNonNull(r, "rule must not be null"));
    requirePrepared(container);
    final List<RuleHandle> handles = new ArrayList<>(rules.size());
    for (final DnsRule r : rules) {
      handles.add(applySingle(container, r));
    }
    return handles;
  }

  @Override
  public void remove(final GenericContainer<?> container, final RuleHandle handle) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(handle, "handle must not be null");
    requirePrepared(container);
    registry
        .remove(container, handle)
        .ifPresent(entry -> transport.removeRules(container, entry.handle().owner()));
  }

  @Override
  public void removeAll(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    requirePrepared(container);
    for (final RuleRegistry.Entry e : registry.removeAll(container)) {
      try {
        transport.removeRules(container, e.handle().owner());
      } catch (final ChaosOperationFailedException ex) {
        log.warn("Failed to remove rule {} during removeAll; continuing", e.handle().owner(), ex);
      }
    }
  }

  // ==================== AdvancedDnsChaos: forward convenience ====================

  @Override
  public RuleHandle failResolution(
      final GenericContainer<?> container, final String hostname, final EaiErrno errno) {
    return apply(container, DnsRule.eai(DnsSelector.host(hostname), errno));
  }

  @Override
  public RuleHandle slowResolution(
      final GenericContainer<?> container, final String hostname, final Duration delay) {
    return apply(container, DnsRule.latency(DnsSelector.host(hostname), delay));
  }

  @Override
  public RuleHandle rewriteHost(
      final GenericContainer<?> container, final String from, final String to) {
    return apply(container, DnsRule.rewrite(DnsSelector.host(from), to));
  }

  @Override
  public RuleHandle rewriteService(
      final GenericContainer<?> container, final String hostname, final String to) {
    return apply(container, DnsRule.service(DnsSelector.host(hostname), to));
  }

  @Override
  public RuleHandle overrideAnswer(
      final GenericContainer<?> container, final String hostname, final List<InetAddress> answers) {
    return apply(container, DnsRule.override(DnsSelector.host(hostname), answers));
  }

  @Override
  public RuleHandle filterFamily(
      final GenericContainer<?> container, final String hostname, final AddressFamily family) {
    return apply(container, DnsRule.filterFamily(DnsSelector.host(hostname), family));
  }

  @Override
  public RuleHandle limitAnswers(
      final GenericContainer<?> container, final String hostname, final int max) {
    return apply(container, DnsRule.limit(DnsSelector.host(hostname), max));
  }

  @Override
  public RuleHandle shuffleAnswers(final GenericContainer<?> container, final String hostname) {
    return apply(container, DnsRule.shuffle(DnsSelector.host(hostname)));
  }

  // ==================== AdvancedDnsChaos: reverse convenience ====================

  @Override
  public RuleHandle failReverse(
      final GenericContainer<?> container, final String address, final EaiErrno errno) {
    return apply(container, DnsRule.eai(reverseSelectorFor(address), errno));
  }

  @Override
  public RuleHandle slowReverse(
      final GenericContainer<?> container, final String address, final Duration delay) {
    return apply(container, DnsRule.latency(reverseSelectorFor(address), delay));
  }

  @Override
  public RuleHandle rewriteReverseHost(
      final GenericContainer<?> container, final String address, final String to) {
    return apply(container, DnsRule.rewrite(reverseSelectorFor(address), to));
  }

  @Override
  public RuleHandle rewriteReverseService(
      final GenericContainer<?> container, final String address, final String to) {
    return apply(container, DnsRule.service(reverseSelectorFor(address), to));
  }

  // ==================== AdvancedDnsChaos: raw selector escape hatch ====================

  @Override
  public RuleHandle eai(
      final GenericContainer<?> container, final DnsSelector selector, final EaiErrno errno) {
    return apply(container, DnsRule.eai(selector, errno));
  }

  // ==================== Internal helpers ====================

  private RuleHandle applySingle(final GenericContainer<?> container, final DnsRule rule) {
    final RuleHandle handle = new RuleHandle(nextOwner());
    transport.addRule(container, handle.owner(), DnsRuleSerializer.serialize(rule));
    registry.register(container, new RuleRegistry.Entry(handle, rule));
    return handle;
  }

  private String nextOwner() {
    return "r" + ownerCounter.incrementAndGet();
  }

  private void requirePrepared(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      throw new LibchaosNotPreparedException(LibchaosLib.DNS.getShortName(), container);
    }
  }

  /**
   * Picks {@link DnsSelector#reverseIpv4} or {@link DnsSelector#reverseIpv6} depending on the
   * literal form. Hostnames are rejected — reverse selectors require numeric IP literals.
   */
  private static DnsSelector reverseSelectorFor(final String address) {
    Objects.requireNonNull(address, "address must not be null");
    if (address.indexOf(':') >= 0) {
      return DnsSelector.reverseIpv6(address);
    }
    return DnsSelector.reverseIpv4(address);
  }
}
