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
 * Injects {@code EAI_SYSTEM} into every {@code getnameinfo(3)} call (reverse DNS lookup), causing
 * the call to return {@code EAI_SYSTEM} as if an underlying system call failed during the reverse
 * name resolution and set {@code errno} to a specific POSIX error code.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code REVERSE}, errno = {@code
 * EAI_SYSTEM}) tuple. This annotation always fires on every intercepted reverse lookup — there is
 * no per-call probability field. Use it when you need every reverse resolution attempt to fail with
 * a system-level error so that the application's handling of resolver-infrastructure faults in the
 * reverse-lookup path is exercised. No runtime selector-errno validation is needed.
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
 *       EAI_SYSTEM} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every reverse lookup fails with a system-error indicator; the application must inspect the
 *       secondary {@code errno} to produce a meaningful diagnostic, rather than reporting only the
 *       opaque {@code EAI_SYSTEM} code.
 *   <li>The injected {@code EAI_SYSTEM} is not associated with a real underlying {@code errno}
 *       value; assert that the application handles the case where {@code errno} may be zero or
 *       undefined when {@code EAI_SYSTEM} is received.
 *   <li>Assert that the application falls back to logging the raw IP address and does not propagate
 *       the system error to callers who have no way to remediate a resolver failure.
 * </ul>
 *
 * <p>In production, {@code EAI_SYSTEM} from {@code getnameinfo} occurs when the resolver encounters
 * a syscall failure while constructing the PTR query — for example, when file descriptor limits are
 * exhausted and the resolver cannot open a UDP socket, or when the process receives a signal during
 * the resolver's internal I/O operations.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EAI_SYSTEM} from {@code getnameinfo} follows the same semantics as from {@code
 * getaddrinfo}: it indicates a resolver-internal syscall failure with the specific error available
 * in {@code errno}. For reverse lookups, this most commonly occurs when the resolver cannot open a
 * socket to send the PTR query, or when an internal {@code read} or {@code write} on the resolver's
 * socket fails with a syscall error.
 *
 * <p>The hostname output buffer passed to {@code getnameinfo} is not written when the function
 * returns a non-zero error code. Application code that reads from the output buffer after an {@code
 * EAI_SYSTEM} return will read uninitialized memory. This injection exercises that code path safely
 * — the interposer returns the error code immediately, so the output buffer can be verified to
 * remain unmodified (e.g. zero-filled or null-terminated with no hostname content).
 *
 * <p>Java's {@code InetAddress.getHostName()} silently suppresses all {@code getnameinfo} errors
 * and returns the dotted-decimal IP string. Application code that calls native resolver APIs via
 * JNI or through a library like Netty's DNS resolver must explicitly handle {@code EAI_SYSTEM} and
 * verify that the secondary {@code errno} is included in any diagnostic output.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosReverseEaisystem
 * class ReverseEaisystemTest {
 *   @Test
 *   void diagnosticIncludesEaiSystemCodeWhenReverseLookupFails(ConnectionInfo info) {
 *     // assert that the error report includes EAI_SYSTEM and the secondary errno
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReverseEaifail
 * @see ChaosReverseEaiagain
 * @see ChaosForwardEaisystem
 * @see ChaosWildcardEaisystem
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosReverseEaisystem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.REVERSE, errno = EaiErrno.EAI_SYSTEM)
public @interface ChaosReverseEaisystem {

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
   * @ChaosReverseEaisystem(id = "primary",  probability = 0.001)
   * @ChaosReverseEaisystem(id = "replica",  probability = 0.01)
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
    ChaosReverseEaisystem[] value();
  }
}
