/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.reverse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.dns.annotation.l1.DnsLatencyBinding;
import com.macstab.chaos.dns.annotation.l1.DnsSelectorKind;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardLatency;
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardLatency;

/**
 * Delays every {@code getnameinfo(3)} call (reverse DNS lookup) by an additional {@link #delayMs}
 * milliseconds before delegating to the real resolver, causing reverse name resolution to succeed
 * but take longer than the application expects.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code REVERSE}, effect = LATENCY)
 * tuple. Unlike EAI errno variants, the latency primitive always delegates to the real resolver
 * after the configured extra delay — the return value reflects the actual DNS response. No runtime
 * selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted {@code getnameinfo} call the interposer first sleeps for an additional
 *       {@link #delayMs} milliseconds.
 *   <li>After the extra delay, the real resolver call is issued; the result (success or error) is
 *       returned to the application unchanged.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Access log entries are written more slowly than normal because the reverse lookup phase is
 *       extended; applications that block request handling on access log completion will exhibit
 *       elevated response latency.
 *   <li>Security components that perform hostname validation before processing a request will
 *       introduce a delay proportional to {@link #delayMs} on every incoming connection; assert
 *       that request throughput degradation is bounded.
 *   <li>Observability pipelines that enrich spans with peer hostnames will increase span recording
 *       latency; assert that the pipeline is non-blocking or that a timeout terminates the lookup.
 *   <li>Assert that reverse lookups are performed asynchronously or on a dedicated thread pool
 *       rather than inline in the critical path, by verifying that request latency does not
 *       increase proportionally with {@link #delayMs}.
 * </ul>
 *
 * <p>In production, slow reverse DNS lookups occur when the reverse DNS zone is delegated to an
 * overloaded nameserver, when the IP block's PTR records have long TTLs that expire during peak
 * load and cause a flood of uncached queries, or when the network path to the reverse-zone
 * nameserver experiences increased latency.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code getnameinfo(3)} with the {@code NI_NAMEREQD} flag sends a PTR query to the resolver and
 * blocks until the response arrives. Without {@code NI_NAMEREQD}, the function can return a numeric
 * address string immediately without making any DNS query — in which case the latency injection
 * still fires but merely delays an operation that would otherwise be instant.
 *
 * <p>Many applications call {@code getnameinfo} in a fire-and-forget background thread to enrich
 * log entries without blocking request processing. The injected latency delays only the background
 * enrichment, not the foreground request. This scenario is benign for latency-sensitive request
 * handling but can cause a background thread pool to exhaust if the enrichment queue grows faster
 * than the delayed lookups can complete.
 *
 * <p>Reverse DNS is frequently called at much lower frequency than forward DNS, because most
 * applications resolve service hostnames at startup (forward) but look up peer hostnames only for
 * each inbound connection (reverse). A large {@link #delayMs} on reverse lookups specifically tests
 * the per-connection overhead rather than the startup overhead.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosReverseLatency(delayMs = 300)
 * class ReverseLatencyTest {
 *   @Test
 *   void requestThroughputIsNotBoundedByReverseLookupLatency(ConnectionInfo info) {
 *     // assert that throughput is not reduced in proportion to the reverse lookup delay
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardLatency
 * @see ChaosWildcardLatency
 * @see com.macstab.chaos.dns.annotation.l1.DnsLatencyBinding
 */
@Repeatable(ChaosReverseLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsLatencyTranslator")
@DnsLatencyBinding(selectorKind = DnsSelectorKind.REVERSE)
public @interface ChaosReverseLatency {

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
   * @ChaosReverseLatency(id = "primary",  probability = 0.001)
   * @ChaosReverseLatency(id = "replica",  probability = 0.01)
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
    ChaosReverseLatency[] value();
  }
}
