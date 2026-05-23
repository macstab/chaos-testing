/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.forward;

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
 * Delays every {@code getaddrinfo(3)} call (forward DNS lookup) by an additional {@link #delayMs}
 * milliseconds before delegating to the real resolver, causing name resolution to succeed but take
 * longer than the application expects.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code FORWARD}, effect = LATENCY)
 * tuple. Unlike EAI errno variants, the latency primitive always delegates to the real resolver
 * after the configured extra delay — the return value reflects the actual DNS response. No runtime
 * selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted {@code getaddrinfo} call the interposer first sleeps for an additional
 *       {@link #delayMs} milliseconds.
 *   <li>After the extra delay, the real resolver call is issued; the result (success or error) is
 *       returned to the application unchanged.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Connection establishment takes longer than normal because the DNS round trip is extended;
 *       application-level connection timeouts that are tighter than the configured DNS timeout plus
 *       {@link #delayMs} will expire before the socket is opened.
 *   <li>Connection pools that resolve hostnames synchronously at startup will take longer to
 *       initialise; health-check probes that run before startup completes may report false
 *       positives.
 *   <li>HTTP client libraries that budget a single timeout across DNS resolution, TCP connect, and
 *       TLS handshake will exhaust their budget in the DNS phase, making the TCP and TLS code
 *       paths unreachable.
 *   <li>Assert that DNS resolution timeouts are configured independently from connection timeouts
 *       and that the application correctly distinguishes a DNS-timeout failure from a
 *       connection-refused failure.
 * </ul>
 *
 * <p>In production, slow {@code getaddrinfo} calls occur when the upstream DNS server is under
 * load, when the resolver's UDP packets are dropped by a firewall and must wait for a
 * retransmission timeout, or when the container's DNS search domain is misconfigured and causes
 * multiple sequential lookups to fail before reaching the correct qualified name.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code getaddrinfo(3)} is a blocking call that internally sends one or more UDP (or TCP)
 * DNS queries and waits for responses. On Linux with glibc, the default resolver timeout is
 * 5 seconds per nameserver with 2 retries, giving a worst-case latency of 15 seconds before the
 * call returns {@code EAI_AGAIN}. The injected extra delay is added on top of the actual resolver
 * round trip, which in a test environment completes in milliseconds; the total duration observed
 * by the application is therefore approximately {@link #delayMs} plus the real resolver latency.
 *
 * <p>Java's {@code InetAddress.getByName()} is synchronous and blocks the calling thread for the
 * full duration of the DNS lookup. Applications that call it on a Netty event-loop thread or an
 * RxJava computation thread will block those threads, starving all other I/O on the same event
 * loop. Injecting forward latency reveals these blocking-DNS patterns in code review by observing
 * that all concurrent operations stall during the delay window.
 *
 * <p>Asynchronous DNS libraries (c-ares, Netty's {@code DnsNameResolver}) perform the lookup in
 * a separate thread or event loop and will not block application threads. The forward latency
 * injection still delays the completion of the lookup, which delays the connection establishment
 * — making it useful for testing the timeout and cancellation behaviour of async DNS pipelines.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosForwardLatency(delayMs = 500)
 * class ForwardLatencyTest {
 *   @Test
 *   void connectionTimeoutExceedsWhenDnsIsSlowButSucceeds(ConnectionInfo info) {
 *     // assert that the application's DNS timeout is configured independently
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReverseLatency
 * @see ChaosWildcardLatency
 * @see com.macstab.chaos.dns.annotation.l1.DnsLatencyBinding
 */
@Repeatable(ChaosForwardLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsLatencyTranslator")
@DnsLatencyBinding(selectorKind = DnsSelectorKind.FORWARD)
public @interface ChaosForwardLatency {

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
   * @ChaosForwardLatency(id = "primary",  probability = 0.001)
   * @ChaosForwardLatency(id = "replica",  probability = 0.01)
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
    ChaosForwardLatency[] value();
  }
}
