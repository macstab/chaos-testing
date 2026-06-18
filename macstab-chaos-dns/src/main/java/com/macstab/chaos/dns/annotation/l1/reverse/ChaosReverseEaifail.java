/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.reverse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.dns.annotation.l1.DnsEaiBinding;
import com.macstab.chaos.dns.annotation.l1.DnsSelectorKind;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaifail;
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardEaifail;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Injects {@code EAI_FAIL} into every {@code getnameinfo(3)} call (reverse DNS lookup), causing the
 * call to return {@code EAI_FAIL} as if the reverse DNS authoritative server returned a permanent
 * failure response for the queried IP address.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code REVERSE}, errno = {@code
 * EAI_FAIL}) tuple. This annotation always fires on every intercepted reverse lookup — there is no
 * per-call probability field. Use it when you need every reverse resolution attempt to fail
 * permanently so that the application's hard-failure handling for PTR records is exercised. No
 * runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted {@code getnameinfo} call the interposer immediately returns {@code
 *       EAI_FAIL} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every reverse lookup attempt fails permanently; the application must treat the absence of a
 *       PTR record as a normal operating condition and fall back to using the raw IP address for
 *       logging, audit trails, and access control decisions.
 *   <li>Security components that block connections when hostname validation fails will reject all
 *       incoming connections under this injection; assert that the application applies the correct
 *       default-deny or default-permit policy when reverse DNS is unavailable.
 *   <li>Distributed tracing and observability pipelines that enrich spans with peer hostnames will
 *       receive empty or null hostname fields; assert that the enrichment logic handles this
 *       without throwing or recording malformed spans.
 * </ul>
 *
 * <p>In production, {@code EAI_FAIL} from {@code getnameinfo} occurs when no PTR record exists for
 * the IP address (common for cloud ephemeral IPs), when the reverse DNS zone is misconfigured or
 * delegated to a non-responsive nameserver, or when the authoritative server for the reverse zone
 * returns {@code SERVFAIL}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Reverse DNS ({@code getnameinfo}) queries a PTR record in the {@code in-addr.arpa} zone for
 * IPv4 or the {@code ip6.arpa} zone for IPv6. In many cloud environments, ephemeral compute
 * instances do not have PTR records; calls to {@code getnameinfo} for their IPs return {@code
 * EAI_NONAME} or {@code EAI_FAIL}. This is a normal operating condition that application code must
 * handle, but it is rarely exercised in development environments where PTR records exist for the
 * developer's machine.
 *
 * <p>The distinction between {@code EAI_FAIL} (permanent) and {@code EAI_AGAIN} (transient) is
 * significant for caching decisions: a permanent failure can be cached with a long TTL to avoid
 * repeated queries, while a transient failure should be retried. Injecting {@code EAI_FAIL}
 * specifically tests whether the application's reverse-lookup cache correctly stores and reuses
 * permanent failure results.
 *
 * <p>Java's {@code InetAddress.getHostName()} calls {@code getnameinfo} internally and silently
 * returns the IP address string when the reverse lookup fails, without surfacing the {@code
 * EAI_FAIL} error to the caller. Application code that uses {@code getHostName()} will therefore
 * never observe this injection; only code that calls native resolver APIs directly or via JNI will
 * see the returned error code.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosReverseEaifail
 * class ReverseEaifailTest {
 *   @Test
 *   void accessLogsContainRawIpWhenReverseLookupPermanentlyFails(ConnectionInfo info) {
 *     // assert that the access log records the IP address and not an empty hostname
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReverseEaiagain
 * @see ChaosForwardEaifail
 * @see ChaosWildcardEaifail
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosReverseEaifail.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.REVERSE, errno = EaiErrno.EAI_FAIL)
public @interface ChaosReverseEaifail {

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
   * @ChaosReverseEaifail(id = "primary",  probability = 0.001)
   * @ChaosReverseEaifail(id = "replica",  probability = 0.01)
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
    ChaosReverseEaifail[] value();
  }
}
