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
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaisystem;
import com.macstab.chaos.dns.annotation.l1.reverse.ChaosReverseEaisystem;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Injects {@code EAI_SYSTEM} into both {@code getaddrinfo(3)} (forward lookup) and {@code
 * getnameinfo(3)} (reverse lookup), causing every DNS resolver call to return {@code EAI_SYSTEM} as
 * if an underlying system call failed during name resolution.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code WILDCARD}, errno = {@code
 * EAI_SYSTEM}) tuple. The {@code WILDCARD} selector matches both interposed DNS calls
 * simultaneously — equivalent to applying {@link ChaosForwardEaisystem} and {@link
 * ChaosReverseEaisystem} in a single annotation. This annotation always fires on every intercepted
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
 *       EAI_SYSTEM} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Both forward and reverse DNS resolution fail with a system-error indicator; the application
 *       must inspect the secondary {@code errno} for both call paths and include it in diagnostics.
 *   <li>The secondary {@code errno} is not populated by the injection (it defaults to zero or the
 *       previous value); assert that the application handles zero {@code errno} when {@code
 *       EAI_SYSTEM} is received.
 *   <li>Assert that the application's DNS error handling never directly propagates a system {@code
 *       errno} value as the primary user-facing error code; it should always translate the error
 *       into a domain-specific exception or message.
 * </ul>
 *
 * <p>In production, simultaneous {@code EAI_SYSTEM} on both DNS APIs occurs when the process
 * exhausts its file descriptors and the resolver cannot open sockets for either forward or reverse
 * queries, or when a signal interrupts both resolver socket operations simultaneously.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} {@code EAI_SYSTEM} injection is the most general DNS-infrastructure
 * failure mode: both resolution APIs fail with an opaque system error, requiring the application to
 * handle a case where the error description is "look at errno" but errno may not be set. This
 * exercises the defensive handling posture of application code that always checks both the {@code
 * EAI_*} return code and the secondary {@code errno} before constructing a diagnostic.
 *
 * <p>{@code EAI_SYSTEM} from both DNS APIs simultaneously is a strong signal that a process-level
 * resource limit has been reached. The most common cause is file descriptor exhaustion: the
 * resolver needs to open a UDP socket for each query, and if {@code socket(2)} returns {@code
 * EMFILE} (too many open files), both {@code getaddrinfo} and {@code getnameinfo} will return
 * {@code EAI_SYSTEM} with {@code errno = EMFILE}. This injection simulates that condition without
 * requiring actual fd exhaustion.
 *
 * <p>Sibling per-call annotations ({@link ChaosForwardEaisystem} and {@link ChaosReverseEaisystem})
 * allow targeted injection to a single resolver API when forward and reverse system-error paths
 * need to be tested independently.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosWildcardEaisystem
 * class WildcardEaisystemTest {
 *   @Test
 *   void diagnosticAlwaysIncludesEaiCodeEvenWhenErrnoIsZero(ConnectionInfo info) {
 *     // assert that the error report identifies EAI_SYSTEM and handles absent errno gracefully
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaisystem
 * @see ChaosReverseEaisystem
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosWildcardEaisystem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.WILDCARD, errno = EaiErrno.EAI_SYSTEM)
public @interface ChaosWildcardEaisystem {

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
   * @ChaosWildcardEaisystem(id = "primary",  probability = 0.001)
   * @ChaosWildcardEaisystem(id = "replica",  probability = 0.01)
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
    ChaosWildcardEaisystem[] value();
  }
}
