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
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardEainoname;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Injects {@code EAI_NONAME} into every {@code getaddrinfo(3)} call (forward DNS lookup), causing
 * the call to return {@code EAI_NONAME} as if the hostname does not exist in the DNS namespace.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code FORWARD}, errno = {@code
 * EAI_NONAME}) tuple. This annotation always fires on every intercepted forward lookup — there is
 * no per-call probability field. Use it when you need every resolution attempt to produce an
 * NXDOMAIN-equivalent failure so that the application's host-not-found handling is exercised. No
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
 *   <li>On every intercepted {@code getaddrinfo} call the interposer immediately returns {@code
 *       EAI_NONAME} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every forward resolution attempt fails as if the hostname does not exist; the application
 *       must not retry on {@code EAI_NONAME} as if it were a transient error — the name does not
 *       exist and retrying will produce the same result.
 *   <li>Service-discovery clients that expect the hostname to be present will fail to resolve and
 *       must either fall back to a hardcoded IP, use a service mesh sidecar, or surface an error to
 *       the caller.
 *   <li>Java's {@code InetAddress.getByName()} maps {@code EAI_NONAME} to an {@code
 *       UnknownHostException} with message "Name or service not known"; assert that the application
 *       catches this and emits a domain-specific error rather than an NPE.
 *   <li>Assert that the application correctly distinguishes between "name does not exist" and "name
 *       exists but cannot be reached" when constructing its diagnostic messages.
 * </ul>
 *
 * <p>In production, {@code EAI_NONAME} from {@code getaddrinfo} occurs when a DNS record is deleted
 * (service decommission), when the application connects to the wrong environment (e.g. a
 * development hostname in a production configuration), or when a Kubernetes service is deleted
 * while the application is running.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EAI_NONAME} is defined by POSIX as "the node or service is not known". It is returned
 * when the authoritative DNS server responds with {@code NXDOMAIN} ({@code RCODE 3}), meaning the
 * queried name definitively does not exist in the zone. This is distinct from {@code EAI_FAIL},
 * which signals a resolver infrastructure failure, and from {@code EAI_AGAIN}, which signals a
 * transient failure.
 *
 * <p>The glibc resolver maps {@code NXDOMAIN} to {@code h_errno = HOST_NOT_FOUND} and then to
 * {@code EAI_NONAME}. Applications that call the deprecated {@code gethostbyname(3)} instead of
 * {@code getaddrinfo(3)} will receive {@code NULL} with {@code h_errno = HOST_NOT_FOUND}; the
 * injection from {@code libchaos-dns.so} targets the {@code getaddrinfo} path specifically, making
 * it necessary to use the modern API to observe the injection.
 *
 * <p>Connection pools that pre-resolve hostnames at startup will fail to initialise under this
 * injection. Pools configured with lazy resolution (connect-on-first-use) will fail at the first
 * connection attempt. Injecting {@code EAI_NONAME} before pool initialisation is the most thorough
 * way to ensure that startup-time DNS failures are handled gracefully rather than causing an
 * unhandled exception in the pool's background thread.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosForwardEainoname
 * class ForwardEainонameTest {
 *   @Test
 *   void applicationFailsCleanlyWhenHostnameDoesNotExist(ConnectionInfo info) {
 *     // assert that an UnknownHostException is correctly wrapped and surfaced
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaiagain
 * @see ChaosForwardEaifail
 * @see ChaosWildcardEainoname
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosForwardEainoname.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.FORWARD, errno = EaiErrno.EAI_NONAME)
public @interface ChaosForwardEainoname {

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
   * @ChaosForwardEainoname(id = "primary",  probability = 0.001)
   * @ChaosForwardEainoname(id = "replica",  probability = 0.01)
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
    ChaosForwardEainoname[] value();
  }
}
