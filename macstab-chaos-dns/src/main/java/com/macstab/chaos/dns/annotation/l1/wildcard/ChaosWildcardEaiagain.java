/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.wildcard;

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
 * Injects {@code EAI_AGAIN} into both {@code getaddrinfo(3)} (forward lookup) and {@code
 * getnameinfo(3)} (reverse lookup), causing every DNS resolver call to return {@code EAI_AGAIN} as
 * if the resolver reported a temporary failure.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code WILDCARD}, errno = {@code
 * EAI_AGAIN}) tuple. The {@code WILDCARD} selector matches both interposed DNS calls simultaneously
 * — equivalent to applying {@link ChaosForwardEaiagain} and {@link ChaosReverseEaiagain} in a
 * single annotation. This annotation always fires on every intercepted DNS call — there is no
 * per-call probability field. No runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted call to either function the interposer immediately returns {@code
 *       EAI_AGAIN} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Both forward and reverse DNS resolution fail transiently on every call; the application
 *       must implement DNS retry with back-off for forward lookups and fall back to raw IP
 *       addresses for reverse lookups.
 *   <li>Connection establishment fails because forward resolution cannot complete; connection pools
 *       that rely on DNS for endpoint discovery will not be able to acquire new connections.
 *   <li>Observability and security components that perform reverse lookups will also fail; assert
 *       that these components degrade independently from the main connection path.
 *   <li>Assert that the application's retry strategy for forward DNS does not block the reverse
 *       lookup path and vice versa.
 * </ul>
 *
 * <p>In production, simultaneous {@code EAI_AGAIN} on both forward and reverse lookups occurs
 * during complete DNS infrastructure outages — CoreDNS crashes, network partitions that isolate the
 * container from its configured nameservers, or DNS amplification attacks that saturate the
 * resolver.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} selector applies {@code EAI_AGAIN} to both {@code getaddrinfo} and {@code
 * getnameinfo} simultaneously. This is the most aggressive DNS chaos primitive: it removes both
 * hostname-to-address and address-to-hostname resolution from the application's environment. Code
 * that relies on either form of DNS will fail, including connection establishment, peer validation,
 * access logging, and distributed tracing enrichment.
 *
 * <p>Applications with asynchronous DNS resolution (Netty's {@code DnsNameResolver}, c-ares) will
 * receive {@code EAI_AGAIN} in their completion callbacks, which must correctly signal failure to
 * waiting callers rather than silently dropping the resolution result. This injection exercises the
 * full async failure path without requiring a DNS infrastructure failure in the test environment.
 *
 * <p>Sibling per-call annotations ({@link ChaosForwardEaiagain} and {@link ChaosReverseEaiagain})
 * allow targeted injection to a single resolver call when the forward and reverse failure paths
 * need to be tested independently.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosWildcardEaiagain
 * class WildcardEaiagainTest {
 *   @Test
 *   void applicationHandlesCompleteDnsOutageGracefully(ConnectionInfo info) {
 *     // assert that the application retries forward DNS and falls back for reverse
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaiagain
 * @see ChaosReverseEaiagain
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosWildcardEaiagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.WILDCARD, errno = EaiErrno.EAI_AGAIN)
public @interface ChaosWildcardEaiagain {

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
   * @ChaosWildcardEaiagain(id = "primary",  probability = 0.001)
   * @ChaosWildcardEaiagain(id = "replica",  probability = 0.01)
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
    ChaosWildcardEaiagain[] value();
  }
}
