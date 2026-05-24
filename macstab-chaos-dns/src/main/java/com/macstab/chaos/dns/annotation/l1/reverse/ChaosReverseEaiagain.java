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
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Injects {@code EAI_AGAIN} into every {@code getnameinfo(3)} call (reverse DNS lookup), causing
 * the call to return {@code EAI_AGAIN} as if the resolver reported a temporary failure while
 * resolving an IP address to a hostname.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code REVERSE}, errno = {@code
 * EAI_AGAIN}) tuple. This annotation always fires on every intercepted reverse lookup — there is no
 * per-call probability field. Use it when you need every reverse resolution attempt to fail
 * transiently so that the application's PTR-record retry logic is exercised. No runtime
 * selector-errno validation is needed.
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
 *       EAI_AGAIN} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every attempt to resolve an IP address to a hostname fails transiently; application code
 *       that logs peer hostnames for auditing or access control must fall back to logging the raw
 *       IP address when reverse resolution fails.
 *   <li>Security components that enforce hostname-based allowlists will not be able to verify the
 *       peer's hostname; assert that the application either rejects the connection or applies a
 *       secure default rather than permitting access unconditionally.
 *   <li>Access logs that normally record hostnames will contain raw IP addresses instead; assert
 *       that downstream log-processing pipelines handle this format variation gracefully.
 *   <li>Assert that the application does not block indefinitely waiting for a successful reverse
 *       lookup that will never arrive.
 * </ul>
 *
 * <p>In production, {@code EAI_AGAIN} from {@code getnameinfo} occurs when the PTR record's
 * authoritative nameserver is temporarily unreachable, when the reverse DNS zone ({@code
 * in-addr.arpa} or {@code ip6.arpa}) is delegated to a resolver that is under load, or during
 * periods of high DNS query volume that cause resolver timeouts.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Reverse DNS lookup ({@code getnameinfo} with {@code NI_NAMEREQD} or without it) queries a PTR
 * record in the {@code in-addr.arpa} or {@code ip6.arpa} zone. This query is sent to a different
 * authoritative nameserver than forward lookups — the one that manages the reverse zone for the IP
 * block. In cloud environments, the reverse zone is often managed by the cloud provider and may
 * have slower or less reliable resolution than the forward zone.
 *
 * <p>Applications that call {@code getnameinfo} in a request-handling thread and treat the reverse
 * lookup as mandatory (e.g. for access logging) will block that thread for the full resolver
 * timeout on each {@code EAI_AGAIN}. Injecting this error forces those calls to return immediately
 * with a failure, revealing whether the application correctly handles the fast-fail case without
 * blocking.
 *
 * <p>The glibc flag {@code NI_NAMEREQD} causes {@code getnameinfo} to return an error if the
 * hostname is not available, rather than falling back to a numeric representation. Applications
 * that use this flag with {@code EAI_AGAIN} must handle the case where reverse resolution is
 * transiently unavailable — which this injection exercises directly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosReverseEaiagain
 * class ReverseEaiagainTest {
 *   @Test
 *   void accessLogFallsBackToIpWhenReverseLookupFails(ConnectionInfo info) {
 *     // assert that the access log contains the raw IP address, not a hostname
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReverseEaifail
 * @see ChaosForwardEaiagain
 * @see ChaosWildcardEaiagain
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosReverseEaiagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.REVERSE, errno = EaiErrno.EAI_AGAIN)
public @interface ChaosReverseEaiagain {

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
   * @ChaosReverseEaiagain(id = "primary",  probability = 0.001)
   * @ChaosReverseEaiagain(id = "replica",  probability = 0.01)
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
    ChaosReverseEaiagain[] value();
  }
}
