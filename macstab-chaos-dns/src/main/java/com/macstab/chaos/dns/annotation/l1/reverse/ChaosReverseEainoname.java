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
 * Injects {@code EAI_NONAME} into every {@code getnameinfo(3)} call (reverse DNS lookup), causing
 * the call to return {@code EAI_NONAME} as if no PTR record exists for the queried IP address.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code REVERSE}, errno =
 * {@code EAI_NONAME}) tuple. This annotation always fires on every intercepted reverse lookup —
 * there is no per-call probability field. Use it when you need every reverse resolution attempt to
 * produce a "no PTR record" failure — the most common production scenario for cloud IP addresses —
 * so that all fallback paths are exercised. No runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.DNS)} on the container definition causes the
 *       extension to upload {@code libchaos-dns.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code getaddrinfo(3)} and {@code getnameinfo(3)} at the
 *       dynamic-linker level.
 *   <li>On every intercepted {@code getnameinfo} call the interposer immediately returns
 *       {@code EAI_NONAME} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every reverse lookup returns "no hostname found"; this is the most realistic failure mode
 *       for cloud environments where most IPs do not have PTR records.
 *   <li>Applications that log peer hostnames for access auditing will record raw IP addresses;
 *       assert that the log format remains valid and parseable when the hostname field is absent.
 *   <li>When {@code getnameinfo} is called with {@code NI_NAMEREQD}, the caller explicitly
 *       requires a hostname and treats absence as an error; assert that the application applies
 *       the correct access policy (permit or deny) when the hostname requirement cannot be met.
 *   <li>Assert that the application does not cache a null hostname indefinitely and re-attempts
 *       the lookup when the TTL expires.
 * </ul>
 *
 * <p>In production, {@code EAI_NONAME} from {@code getnameinfo} occurs when no PTR record has
 * been configured for the IP address — the standard situation for cloud compute instances,
 * container overlay networks, and most private-range IPs. This is the default failure mode for
 * reverse DNS, not an exceptional one.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>For reverse lookups, {@code EAI_NONAME} signals that the authoritative nameserver for the
 * reverse zone responded with {@code NXDOMAIN}, meaning the PTR record does not exist. This is
 * equivalent to the forward {@code EAI_NONAME} case, but the semantic implication is different:
 * a missing forward record means the service does not exist; a missing PTR record means the IP
 * address has not been registered in the reverse DNS zone, which is normal for most IPs.
 *
 * <p>glibc's {@code getnameinfo} without {@code NI_NAMEREQD} falls back to a numeric
 * representation of the address when the lookup fails, effectively converting the error into a
 * no-op from the caller's perspective. With {@code NI_NAMEREQD} set, the function returns an
 * error code instead. This injection exercises the {@code NI_NAMEREQD} path by returning
 * {@code EAI_NONAME} regardless of the flags, making it useful for testing code that handles
 * both cases explicitly.
 *
 * <p>In Kubernetes environments, the overlay network's IP addresses typically have no PTR records
 * in the cluster DNS ({@code .svc.cluster.local} forward records exist, but there is usually no
 * corresponding {@code in-addr.arpa} delegation). Applications that rely on {@code getnameinfo}
 * for peer authentication or logging must handle {@code EAI_NONAME} as a first-class result.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosReverseEainoname
 * class ReverseEainонameTest {
 *   @Test
 *   void accessLogRecordsRawIpWhenNoPtrRecordExists(ConnectionInfo info) {
 *     // assert that the access log uses the raw IP address and not null or empty
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReverseEaiagain
 * @see ChaosReverseEaifail
 * @see ChaosForwardEainoname
 * @see ChaosWildcardEainoname
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosReverseEainoname.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.REVERSE, errno = EaiErrno.EAI_NONAME)
public @interface ChaosReverseEainoname {

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
   * @ChaosReverseEainoname(id = "primary",  probability = 0.001)
   * @ChaosReverseEainoname(id = "replica",  probability = 0.01)
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
    ChaosReverseEainoname[] value();
  }
}
