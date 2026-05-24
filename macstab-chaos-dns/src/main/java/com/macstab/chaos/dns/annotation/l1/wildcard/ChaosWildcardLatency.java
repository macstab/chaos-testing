/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.wildcard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.dns.annotation.l1.DnsLatencyBinding;
import com.macstab.chaos.dns.annotation.l1.DnsSelectorKind;

/**
 * Delays both {@code getaddrinfo(3)} (forward lookup) and {@code getnameinfo(3)} (reverse lookup)
 * by an additional {@link #delayMs} milliseconds before delegating to the real resolver, making all
 * DNS resolution slower than the application expects.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code WILDCARD}, effect = LATENCY)
 * tuple. The {@code WILDCARD} selector matches both interposed DNS calls simultaneously —
 * equivalent to applying {@link ChaosForwardLatency} and {@link ChaosReverseLatency} in a single
 * annotation. Unlike EAI errno variants, the latency primitive always delegates to the real
 * resolver after the configured extra delay — the return value reflects the actual DNS response. No
 * runtime selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted call to either function the interposer first sleeps for an additional
 *       {@link #delayMs} milliseconds.
 *   <li>After the extra delay, the real resolver call is issued; the result (success or error) is
 *       returned to the application unchanged.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Connection establishment is slower because forward resolution takes {@link #delayMs} ms
 *       longer; application-level connection timeouts that budget less than the DNS delay plus the
 *       actual resolver round trip will expire before the socket is opened.
 *   <li>Reverse lookups for access logging, audit trails, and peer validation are delayed by the
 *       same amount; request processing that waits for these enrichments will appear slower.
 *   <li>The combination exercises the scenario where a degraded upstream DNS server makes all
 *       resolution slow simultaneously — a situation that is hard to reproduce in test environments
 *       without disrupting shared infrastructure.
 *   <li>Assert that the application's total connection timeout budget explicitly accounts for DNS
 *       resolution latency and that reverse lookups are never on the critical path.
 * </ul>
 *
 * <p>In production, simultaneous latency on both DNS APIs occurs when the upstream DNS server is
 * under load, when the resolver's UDP packets are queued behind other traffic on a congested
 * network link, or when the DNS search domain list causes multiple sequential lookups per query.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} latency injection is the most comprehensive DNS-overhead primitive: it
 * adds the configured delay to every resolver call regardless of direction. This simulates a
 * degraded but functional DNS infrastructure where all queries succeed eventually, but only after a
 * longer-than-expected round trip.
 *
 * <p>For applications that call {@code getaddrinfo} synchronously on connection establishment,
 * {@link #delayMs} is added to the observed connection latency. For applications that call {@code
 * getnameinfo} asynchronously for logging enrichment, the same delay is added to the background
 * enrichment thread, which may cause the thread pool to back up if connections arrive faster than
 * enrichments complete.
 *
 * <p>HTTP client libraries that budget a single total timeout across DNS resolution, TCP connect,
 * and TLS handshake (e.g. OkHttp's {@code callTimeout}) will exhaust their budget in the DNS phase
 * when the injected delay is large, making the TCP and TLS code paths unreachable. This injection
 * reveals those hidden assumptions without requiring real network infrastructure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosWildcardLatency(delayMs = 500)
 * class WildcardLatencyTest {
 *   @Test
 *   void connectionTimeoutBudgetExplicitlyAccountsForDnsLatency(ConnectionInfo info) {
 *     // assert that DNS timeout is budgeted separately from connect and TLS timeout
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardLatency
 * @see ChaosReverseLatency
 * @see com.macstab.chaos.dns.annotation.l1.DnsLatencyBinding
 */
@Repeatable(ChaosWildcardLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsLatencyTranslator")
@DnsLatencyBinding(selectorKind = DnsSelectorKind.WILDCARD)
public @interface ChaosWildcardLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-dns
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosWildcardLatency(id = "primary",  probability = 0.001)
   * @ChaosWildcardLatency(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosWildcardLatency[] value();
  }
}
