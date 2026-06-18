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
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardEaisystem;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Injects {@code EAI_SYSTEM} into every {@code getaddrinfo(3)} call (forward DNS lookup), causing
 * the call to return {@code EAI_SYSTEM} as if an underlying system call failed during name
 * resolution and set {@code errno} to a specific POSIX error code.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code FORWARD}, errno = {@code
 * EAI_SYSTEM}) tuple. This annotation always fires on every intercepted forward lookup — there is
 * no per-call probability field. Use it when you need every resolution attempt to fail with a
 * system-level error so that the application's handling of resolver-infrastructure faults is
 * exercised. No runtime selector-errno validation is needed.
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
 *       EAI_SYSTEM} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EAI_SYSTEM} carries an additional system {@code errno} value that callers must
 *       inspect to diagnose the root cause; applications that log only the {@code EAI_*} code
 *       without examining {@code errno} will produce incomplete diagnostics.
 *   <li>The injected {@code EAI_SYSTEM} is not associated with a specific underlying {@code errno},
 *       so the application must handle the case where {@code errno} is zero or undefined when
 *       {@code EAI_SYSTEM} is received.
 *   <li>Assert that the application always calls {@code gai_strerror(EAI_SYSTEM)} in addition to
 *       checking {@code errno} and that both values appear in the error report.
 * </ul>
 *
 * <p>In production, {@code EAI_SYSTEM} from {@code getaddrinfo} occurs when the resolver cannot
 * open a UDP socket to send the DNS query (file descriptor exhaustion, {@code EMFILE}), when a
 * network interface disappears during the resolver's UDP send ({@code ENETDOWN}), or when the
 * process's resource limits prevent the resolver from completing a system call it needs.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EAI_SYSTEM} is defined by POSIX as "system error returned in errno". It is the escape
 * hatch that allows {@code getaddrinfo} to signal syscall-level failures that do not map to any of
 * the named {@code EAI_*} codes. When the glibc resolver encounters a syscall failure (e.g. {@code
 * socket(2)} returns {@code EMFILE} when trying to open a UDP socket for the query), it returns
 * {@code EAI_SYSTEM} and leaves {@code errno} set to the syscall's error code.
 *
 * <p>Application code that handles {@code EAI_*} errors with a switch statement and has a {@code
 * default: throw new RuntimeException()} branch will typically reach that branch on {@code
 * EAI_SYSTEM}, revealing whether the exception message includes both the resolver error code and
 * the underlying {@code errno}. This injection makes that code path reachable without requiring
 * file descriptor exhaustion or network interface failure in the test environment.
 *
 * <p>Java's {@code InetAddress} maps all {@code EAI_*} codes to {@code UnknownHostException} and
 * does not expose the original code or the secondary {@code errno}. Native DNS clients accessed via
 * JNI (c-ares, libadns) preserve the {@code EAI_SYSTEM} code in their own error structures; this
 * injection ensures those structures are correctly populated and surfaced to the Java caller.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosForwardEaisystem
 * class ForwardEaisystemTest {
 *   @Test
 *   void applicationIncludesBothEaiCodeAndErrnoInDiagnostic(ConnectionInfo info) {
 *     // assert that the error report contains the EAI_SYSTEM code and an errno description
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaifail
 * @see ChaosForwardEaiagain
 * @see ChaosWildcardEaisystem
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosForwardEaisystem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.FORWARD, errno = EaiErrno.EAI_SYSTEM)
public @interface ChaosForwardEaisystem {

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
   * @ChaosForwardEaisystem(id = "primary",  probability = 0.001)
   * @ChaosForwardEaisystem(id = "replica",  probability = 0.01)
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
    ChaosForwardEaisystem[] value();
  }
}
