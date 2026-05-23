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
 * Injects {@code EAI_FAIL} into both {@code getaddrinfo(3)} (forward lookup) and
 * {@code getnameinfo(3)} (reverse lookup), causing every DNS resolver call to return
 * {@code EAI_FAIL} as if the authoritative nameserver returned a permanent failure response.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code WILDCARD}, errno =
 * {@code EAI_FAIL}) tuple. The {@code WILDCARD} selector matches both interposed DNS calls
 * simultaneously — equivalent to applying {@link ChaosForwardEaifail} and
 * {@link ChaosReverseEaifail} in a single annotation. This annotation always fires on every
 * intercepted DNS call — there is no per-call probability field. No runtime selector-errno
 * validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted call to either function the interposer immediately returns
 *       {@code EAI_FAIL} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Both forward and reverse DNS resolution fail permanently; the application must not retry
 *       either and must fail fast with a structured error for forward resolution, while falling
 *       back to raw IP addresses for reverse resolution.
 *   <li>Connection establishment is impossible because forward resolution cannot complete; any
 *       component that tries to open a connection to a hostname will receive a failure
 *       immediately, with no possibility of recovery through retries.
 *   <li>Applications that distinguish between {@code EAI_FAIL} (permanent) and {@code EAI_AGAIN}
 *       (transient) must not open a retry budget when they receive {@code EAI_FAIL}; assert that
 *       the application terminates the attempt after the first failure.
 * </ul>
 *
 * <p>In production, simultaneous {@code EAI_FAIL} on both forward and reverse DNS occurs when
 * the entire DNS infrastructure has returned a permanent error for the queried names — for example,
 * when the application is misconfigured to use a DNS zone that does not exist, or when a zone
 * deletion is propagated while the application is running.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} selector applies {@code EAI_FAIL} to both resolver APIs simultaneously.
 * Unlike {@code EAI_AGAIN}, which signals a retriable transient failure, {@code EAI_FAIL} signals
 * a non-retriable permanent failure. This distinction is critical for application correctness:
 * code that retries on all {@code EAI_*} errors will spin indefinitely under {@code EAI_FAIL}
 * injection, revealing a missing retry-limit guard.
 *
 * <p>The wildcard form is more aggressive than the per-call forms because it prevents the
 * application from recovering by attempting a reverse lookup when a forward lookup fails (e.g.
 * to find a cached address via PTR record correlation). Both paths fail simultaneously, closing
 * all DNS-based recovery routes.
 *
 * <p>Sibling per-call annotations ({@link ChaosForwardEaifail} and {@link ChaosReverseEaifail})
 * allow targeted injection to a single resolver API when forward and reverse failure paths need
 * to be tested independently.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosWildcardEaifail
 * class WildcardEaifailTest {
 *   @Test
 *   void applicationFailsFastWithoutRetryOnPermanentDnsFailure(ConnectionInfo info) {
 *     // assert that the application does not retry and surfaces a structured error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaifail
 * @see ChaosReverseEaifail
 * @see ChaosWildcardEaiagain
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosWildcardEaifail.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.WILDCARD, errno = EaiErrno.EAI_FAIL)
public @interface ChaosWildcardEaifail {

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
   * @ChaosWildcardEaifail(id = "primary",  probability = 0.001)
   * @ChaosWildcardEaifail(id = "replica",  probability = 0.01)
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
    ChaosWildcardEaifail[] value();
  }
}
