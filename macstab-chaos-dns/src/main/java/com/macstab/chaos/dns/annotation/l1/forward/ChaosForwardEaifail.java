/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.forward;

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
 * Injects {@code EAI_FAIL} into every {@code getaddrinfo(3)} call (forward DNS lookup), causing
 * the call to return {@code EAI_FAIL} as if the authoritative name server returned a permanent
 * failure response.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code FORWARD}, errno =
 * {@code EAI_FAIL}) tuple. This annotation always fires on every intercepted forward lookup —
 * there is no per-call probability field. Use it when you need every resolution attempt to fail
 * permanently so that the application's hard-failure code paths are exercised. No runtime
 * selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted {@code getaddrinfo} call the interposer immediately returns
 *       {@code EAI_FAIL} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every hostname resolution fails with a non-retriable error; the application must not retry
 *       indefinitely and must surface a clear diagnostic rather than looping until timeout.
 *   <li>Unlike {@code EAI_AGAIN}, {@code EAI_FAIL} signals a permanent authoritative failure;
 *       applications that treat all DNS errors as transient will over-retry and exhaust their
 *       retry budgets unnecessarily.
 *   <li>Circuit breakers that monitor DNS failures should open faster on {@code EAI_FAIL} than on
 *       {@code EAI_AGAIN}; assert that the breaker distinguishes the two error classes.
 *   <li>Assert that the application fails fast, emits a structured error with the hostname that
 *       could not be resolved, and does not leave partially-initialised subsystems in an undefined
 *       state.
 * </ul>
 *
 * <p>In production, {@code EAI_FAIL} from {@code getaddrinfo} occurs when the authoritative DNS
 * server returns {@code NXDOMAIN} ({@code RCODE 3}) or when a misconfigured zone causes repeated
 * {@code SERVFAIL} responses that the resolver treats as authoritative. It also occurs when the
 * DNS search domain is misconfigured and no matching zone exists.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EAI_FAIL} is defined by POSIX as "non-recoverable failure in name resolution". The
 * glibc resolver returns it when the upstream DNS server returns a response that indicates a
 * permanent error — specifically when {@code h_errno} is set to {@code NO_RECOVERY}. Unlike
 * {@code EAI_NONAME} (which signals "host not found"), {@code EAI_FAIL} indicates a resolver-level
 * infrastructure failure rather than a legitimate NXDOMAIN response.
 *
 * <p>Java's {@code InetAddress.getByName()} maps {@code EAI_FAIL} to an {@code UnknownHostException}
 * with a message that does not distinguish it from {@code EAI_NONAME}. Application code that relies
 * solely on the exception type cannot tell permanent resolution failures from transient ones. This
 * injection makes that limitation visible: an application that retries {@code UnknownHostException}
 * indefinitely will loop forever under this injection, revealing a missing max-retry guard.
 *
 * <p>Kubernetes pods that use DNS for service discovery (e.g. connecting to
 * {@code my-service.default.svc.cluster.local}) will receive {@code EAI_FAIL} if the in-cluster
 * DNS infrastructure (CoreDNS) is unable to answer the query at all. Injecting this error at the
 * application layer simulates that condition without requiring a CoreDNS failure in the test
 * environment.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosForwardEaifail
 * class ForwardEaifailTest {
 *   @Test
 *   void applicationFailsFastOnPermanentDnsFailure(ConnectionInfo info) {
 *     // assert that the application does not retry and emits a structured error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaiagain
 * @see ChaosForwardEainoname
 * @see ChaosWildcardEaifail
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosForwardEaifail.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.FORWARD, errno = EaiErrno.EAI_FAIL)
public @interface ChaosForwardEaifail {

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
   * @ChaosForwardEaifail(id = "primary",  probability = 0.001)
   * @ChaosForwardEaifail(id = "replica",  probability = 0.01)
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
    ChaosForwardEaifail[] value();
  }
}
