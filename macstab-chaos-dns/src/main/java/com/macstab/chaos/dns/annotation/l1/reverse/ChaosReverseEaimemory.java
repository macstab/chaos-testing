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
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaimemory;
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardEaimemory;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Injects {@code EAI_MEMORY} into every {@code getnameinfo(3)} call (reverse DNS lookup), causing
 * the call to return {@code EAI_MEMORY} as if the resolver could not allocate memory needed to
 * resolve an IP address to a hostname.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code REVERSE}, errno = {@code
 * EAI_MEMORY}) tuple. This annotation always fires on every intercepted reverse lookup — there is
 * no per-call probability field. Use it when you need every reverse resolution attempt to fail with
 * an out-of-memory condition so that the application's memory-pressure handling in the
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
 *       EAI_MEMORY} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every reverse lookup fails with a memory-exhaustion indicator; the application must fall
 *       back to the raw IP address without attempting to retry or cache the result.
 *   <li>Components that pre-allocate hostname buffers before calling {@code getnameinfo} must
 *       handle the case where the call fails despite the buffer being available — {@code
 *       EAI_MEMORY} indicates that the resolver's own internal allocation failed, not the caller's
 *       buffer.
 *   <li>Assert that the application does not propagate an {@code OutOfMemoryError} when it receives
 *       {@code EAI_MEMORY} from a native resolver call; the native heap and the JVM heap are
 *       separate allocations.
 * </ul>
 *
 * <p>In production, {@code EAI_MEMORY} from {@code getnameinfo} occurs when the process is near its
 * native memory limit and the glibc resolver cannot allocate the internal structures it needs to
 * send and parse the PTR query. This is more likely in long-running processes that accumulate
 * native memory fragmentation over time.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code getnameinfo(3)} allocates internal buffers for parsing the DNS response and
 * constructing the returned hostname string. When {@code malloc} fails inside the resolver, {@code
 * getnameinfo} returns {@code EAI_MEMORY}. Unlike {@code EAI_FAIL} or {@code EAI_AGAIN}, this error
 * is entirely host-side and indicates a process-level resource exhaustion rather than a DNS
 * infrastructure problem.
 *
 * <p>Because {@code getnameinfo} writes its result into a caller-supplied buffer (rather than
 * returning a heap-allocated pointer as {@code getaddrinfo} does), the allocation that fails is
 * entirely internal to the glibc resolver. The caller's buffer is not populated on {@code
 * EAI_MEMORY} — application code must not read from the output hostname buffer when the return
 * value is non-zero.
 *
 * <p>Java's {@code InetAddress.getHostName()} silently suppresses resolver errors and returns the
 * IP address string on failure. Application code that calls native resolver APIs through JNI must
 * explicitly check the return value and handle {@code EAI_MEMORY} without assuming that the output
 * hostname buffer contains a valid C string.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosReverseEaimemory
 * class ReverseEaimemoryTest {
 *   @Test
 *   void applicationDoesNotReadFromUninitialisedBufferOnReverseLookupMemoryFailure(ConnectionInfo info) {
 *     // assert that the application falls back to the raw IP address safely
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReverseEaiagain
 * @see ChaosReverseEaifail
 * @see ChaosForwardEaimemory
 * @see ChaosWildcardEaimemory
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosReverseEaimemory.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.REVERSE, errno = EaiErrno.EAI_MEMORY)
public @interface ChaosReverseEaimemory {

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
   * @ChaosReverseEaimemory(id = "primary",  probability = 0.001)
   * @ChaosReverseEaimemory(id = "replica",  probability = 0.01)
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
    ChaosReverseEaimemory[] value();
  }
}
