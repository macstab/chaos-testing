/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.api;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DnsChaos;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.dns.model.AddressFamily;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Capability-tier interface exposing libchaos-dns' resolver-boundary fault-injection surface.
 *
 * <p><strong>Pre-flight contract.</strong> Every method on this interface requires that the target
 * container has been prepared with libchaos-dns <em>before</em> {@code container.start()} — the
 * {@code .so} is hooked via {@code LD_PRELOAD}, which the dynamic loader only honours at process
 * launch. Skipping preparation and then invoking any verb here raises {@link
 * LibchaosNotPreparedException} loudly: there is no silent fallback by design. The sanctioned way
 * to satisfy preparation is the {@code @SyscallLevelChaos(LibchaosLib.DNS)} annotation on the test
 * class, which {@code ChaosTestingExtension} reads to drive {@code LibchaosTransport.prepare()}.
 *
 * <p><strong>Capability uplift over {@link DnsChaos}.</strong> The portable parent interface
 * ({@code blockResolution}, {@code delayResolution}) covers what an iptables/{@code resolv.conf}
 * backend can model. This interface adds operations that require resolver-boundary interpose: the
 * full POSIX EAI palette (5 codes), pre-resolution host/service rewrite, fabricated answer
 * synthesis (OVERRIDE), post-resolution transforms (FILTER_FAMILY, LIMIT, SHUFFLE), and reverse
 * lookup ({@code getnameinfo}) fault injection.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface AdvancedDnsChaos extends DnsChaos {

  // ==================== Generic rule API ====================

  /**
   * Apply a single libchaos-dns rule.
   *
   * @param container target container (must be prepared with libchaos-dns)
   * @param rule rule to apply
   * @return handle identifying the rule for later removal
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-dns is not active on {@code container}
   */
  RuleHandle apply(GenericContainer<?> container, DnsRule rule);

  /**
   * Apply a batch of libchaos-dns rules in a single round-trip.
   *
   * <p>Implementations <strong>must</strong> validate every rule before committing any of them
   * (fail-fast semantics). On success, returns one handle per input rule, in the same order.
   *
   * @param container target container (must be prepared with libchaos-dns)
   * @param rules non-null, possibly empty list of rules
   * @return list of handles, one per rule, in the same order as {@code rules}; empty when {@code
   *     rules} is empty
   * @throws NullPointerException if any argument or rule is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-dns is not active on {@code container}
   */
  List<RuleHandle> applyAll(GenericContainer<?> container, List<DnsRule> rules);

  /**
   * Surgically remove a single previously-applied rule.
   *
   * <p>Idempotent — silently no-op if the handle is unknown to this strategy.
   *
   * @param container target container (must be prepared with libchaos-dns)
   * @param handle handle returned by a previous {@link #apply} or {@link #applyAll} call
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-dns is not active on {@code container}
   */
  void remove(GenericContainer<?> container, RuleHandle handle);

  /**
   * Remove every rule this strategy has applied to {@code container}.
   *
   * @param container target container (must be prepared with libchaos-dns)
   * @throws NullPointerException if {@code container} is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-dns is not active on {@code container}
   */
  void removeAll(GenericContainer<?> container);

  // ==================== Convenience verbs — forward ====================

  /**
   * Fail forward resolution of {@code hostname} with the given EAI code.
   *
   * @param container target container
   * @param hostname host whose {@code getaddrinfo()} should fail
   * @param errno EAI code to inject (typically {@link EaiErrno#EAI_NONAME} or {@link
   *     EaiErrno#EAI_FAIL})
   * @return handle for later removal
   */
  RuleHandle failResolution(GenericContainer<?> container, String hostname, EaiErrno errno);

  /**
   * Add latency to forward resolution of {@code hostname}.
   *
   * @param container target container
   * @param hostname host whose {@code getaddrinfo()} should be slowed
   * @param delay non-negative latency to inject
   * @return handle for later removal
   */
  RuleHandle slowResolution(GenericContainer<?> container, String hostname, Duration delay);

  /**
   * Rewrite the queried host name before resolution — applications looking up {@code from} will
   * actually resolve {@code to}.
   *
   * @param container target container
   * @param from host name appearing in the {@code getaddrinfo()} query
   * @param to replacement host name actually resolved
   * @return handle for later removal
   */
  RuleHandle rewriteHost(GenericContainer<?> container, String from, String to);

  /**
   * Rewrite the queried service string before forward resolution — applications looking up service
   * {@code from} on {@code hostname} will see service {@code to} after the rewrite.
   *
   * @param container target container
   * @param hostname host whose forward resolution should have its service token rewritten
   * @param to replacement service text
   * @return handle for later removal
   */
  RuleHandle rewriteService(GenericContainer<?> container, String hostname, String to);

  /**
   * Synthesize a fabricated answer set for {@code hostname} — {@code getaddrinfo()} returns the
   * given addresses instead of consulting the real resolver.
   *
   * @param container target container
   * @param hostname host to override
   * @param answers non-empty list of synthetic answer addresses
   * @return handle for later removal
   */
  RuleHandle overrideAnswer(
      GenericContainer<?> container, String hostname, List<InetAddress> answers);

  /**
   * Filter the answer set for {@code hostname} to a single address family.
   *
   * @param container target container
   * @param hostname host whose answer list to filter
   * @param family family to retain
   * @return handle for later removal
   */
  RuleHandle filterFamily(GenericContainer<?> container, String hostname, AddressFamily family);

  /**
   * Limit the answer set for {@code hostname} to the first {@code max} entries.
   *
   * @param container target container
   * @param hostname host whose answer list to truncate
   * @param max maximum number of entries to retain ({@code >= 0})
   * @return handle for later removal
   */
  RuleHandle limitAnswers(GenericContainer<?> container, String hostname, int max);

  /**
   * Re-link the answer set for {@code hostname} in random order.
   *
   * @param container target container
   * @param hostname host whose answer list to shuffle
   * @return handle for later removal
   */
  RuleHandle shuffleAnswers(GenericContainer<?> container, String hostname);

  // ==================== Convenience verbs — reverse ====================

  /**
   * Fail reverse resolution of {@code address} ({@code getnameinfo()}) with the given EAI code.
   *
   * @param container target container
   * @param address IPv4 or IPv6 literal whose reverse lookup should fail
   * @param errno EAI code to inject
   * @return handle for later removal
   */
  RuleHandle failReverse(GenericContainer<?> container, String address, EaiErrno errno);

  /**
   * Add latency to reverse resolution of {@code address}.
   *
   * @param container target container
   * @param address IPv4 or IPv6 literal whose reverse lookup should be slowed
   * @param delay non-negative latency
   * @return handle for later removal
   */
  RuleHandle slowReverse(GenericContainer<?> container, String address, Duration delay);

  /**
   * Rewrite the host text returned by a successful {@code getnameinfo()} for {@code address}.
   *
   * @param container target container
   * @param address IPv4 or IPv6 literal whose returned host buffer should be rewritten
   * @param to replacement host text
   * @return handle for later removal
   */
  RuleHandle rewriteReverseHost(GenericContainer<?> container, String address, String to);

  /**
   * Rewrite the service text returned by a successful {@code getnameinfo()} for {@code address}.
   *
   * @param container target container
   * @param address IPv4 or IPv6 literal whose returned service buffer should be rewritten
   * @param to replacement service text
   * @return handle for later removal
   */
  RuleHandle rewriteReverseService(GenericContainer<?> container, String address, String to);

  // ==================== Helpers (raw selector) ====================

  /**
   * Apply an EAI fault to any selector — escape hatch when the typed convenience verbs do not fit
   * (e.g. {@link DnsSelector#anyForward} for "fail every {@code getaddrinfo()}").
   *
   * @param container target container
   * @param selector selector
   * @param errno EAI code to inject
   * @return handle for later removal
   */
  RuleHandle eai(GenericContainer<?> container, DnsSelector selector, EaiErrno errno);
}
