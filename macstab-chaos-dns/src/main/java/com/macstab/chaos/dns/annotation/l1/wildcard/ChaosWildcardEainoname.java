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
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEainoname;
import com.macstab.chaos.dns.annotation.l1.reverse.ChaosReverseEainoname;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Injects {@code EAI_NONAME} into both {@code getaddrinfo(3)} (forward lookup) and {@code
 * getnameinfo(3)} (reverse lookup), causing every DNS resolver call to return {@code EAI_NONAME} as
 * if no DNS record exists for the queried name or address.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code WILDCARD}, errno = {@code
 * EAI_NONAME}) tuple. The {@code WILDCARD} selector matches both interposed DNS calls
 * simultaneously — equivalent to applying {@link ChaosForwardEainoname} and {@link
 * ChaosReverseEainoname} in a single annotation. This annotation always fires on every intercepted
 * DNS call — there is no per-call probability field. No runtime selector-errno validation is
 * needed.
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
 *       EAI_NONAME} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Forward lookups fail as if all hostnames do not exist; connection establishment is
 *       impossible because no address can be resolved.
 *   <li>Reverse lookups fail as if no PTR records exist; access logs, audit trails, and security
 *       components fall back to raw IP addresses.
 *   <li>The combination exercises the scenario where the application is misconfigured to use the
 *       wrong DNS zone, causing both forward (NXDOMAIN for service hostnames) and reverse (no PTR
 *       records for the container's overlay IP) lookups to fail simultaneously.
 *   <li>Assert that the application emits a structured connection error for forward failures and
 *       uses raw IP addresses for reverse failures, with no NPEs or empty-string host fields.
 * </ul>
 *
 * <p>In production, simultaneous {@code EAI_NONAME} on both DNS APIs occurs when the application is
 * deployed into an environment with a different DNS zone than expected — for example, when a
 * service is moved between Kubernetes namespaces and the old hostnames are deleted before the
 * application is updated.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>For forward lookups, {@code EAI_NONAME} means the hostname does not exist (NXDOMAIN). For
 * reverse lookups, it means the IP address has no PTR record. The wildcard injection applies both
 * simultaneously, eliminating DNS as a resolution mechanism for the entire application. Code that
 * attempts forward lookups and falls back to cached IPs, then attempts reverse lookups to verify
 * those IPs, will find both strategies blocked.
 *
 * <p>This is the most common production DNS failure mode for cloud-native applications: service
 * hostnames can be deleted (forward {@code EAI_NONAME}), and most overlay network IPs have no PTR
 * records (reverse {@code EAI_NONAME}). Testing both simultaneously gives the highest fidelity
 * simulation of cloud DNS behaviour.
 *
 * <p>Sibling per-call annotations ({@link ChaosForwardEainoname} and {@link ChaosReverseEainoname})
 * allow targeted injection to a single resolver API when forward and reverse failure paths need to
 * be verified independently.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosWildcardEainoname
 * class WildcardEainонameTest {
 *   @Test
 *   void applicationHandlesAbsenceOfAllDnsRecords(ConnectionInfo info) {
 *     // assert that forward failure produces a connection error and reverse failure uses raw IP
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEainoname
 * @see ChaosReverseEainoname
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosWildcardEainoname.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.WILDCARD, errno = EaiErrno.EAI_NONAME)
public @interface ChaosWildcardEainoname {

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
   * @ChaosWildcardEainoname(id = "primary",  probability = 0.001)
   * @ChaosWildcardEainoname(id = "replica",  probability = 0.01)
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
    ChaosWildcardEainoname[] value();
  }
}
