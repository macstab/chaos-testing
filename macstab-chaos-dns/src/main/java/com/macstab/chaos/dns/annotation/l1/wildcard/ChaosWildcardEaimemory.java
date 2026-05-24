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
 * Injects {@code EAI_MEMORY} into both {@code getaddrinfo(3)} (forward lookup) and {@code
 * getnameinfo(3)} (reverse lookup), causing every DNS resolver call to return {@code EAI_MEMORY} as
 * if the resolver could not allocate memory needed to complete the query.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code WILDCARD}, errno = {@code
 * EAI_MEMORY}) tuple. The {@code WILDCARD} selector matches both interposed DNS calls
 * simultaneously — equivalent to applying {@link ChaosForwardEaimemory} and {@link
 * ChaosReverseEaimemory} in a single annotation. This annotation always fires on every intercepted
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
 *       EAI_MEMORY} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Both forward and reverse DNS resolution fail with a memory-exhaustion indicator; the
 *       application must handle both failures independently without propagating an {@code
 *       OutOfMemoryError} — the native heap and JVM heap are separate.
 *   <li>Connection establishment fails because forward resolution returns {@code EAI_MEMORY};
 *       assert that the application emits a structured connection-failure error rather than a
 *       confusing "out of memory" diagnostic.
 *   <li>Access logging and observability enrichment fail for reverse lookups; assert that these
 *       components fall back gracefully to raw IP addresses.
 *   <li>Assert that neither failure path allocates additional objects on the JVM heap that could
 *       trigger a genuine Java {@code OutOfMemoryError} while handling the native memory failure.
 * </ul>
 *
 * <p>In production, simultaneous {@code EAI_MEMORY} on both DNS APIs occurs when the process is
 * near its native memory limit — a condition where both the forward and reverse resolver code paths
 * fail because neither can allocate their internal query buffers.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} {@code EAI_MEMORY} injection tests the most extreme memory-pressure
 * scenario for the DNS subsystem: both resolution paths fail simultaneously due to native allocator
 * exhaustion. This is distinct from the JVM's heap memory — the glibc resolver uses {@code
 * malloc(3)} from the native heap, which is a completely separate allocator.
 *
 * <p>Applications that conflate native {@code EAI_MEMORY} with JVM {@code OutOfMemoryError} will
 * apply the wrong recovery strategy. JVM OOM typically requires garbage collection and may be
 * recoverable; native {@code EAI_MEMORY} indicates that the allocator's arena is exhausted and may
 * not recover without process restart. This injection makes the distinction testable.
 *
 * <p>Sibling per-call annotations ({@link ChaosForwardEaimemory} and {@link ChaosReverseEaimemory})
 * allow targeted injection to a single resolver API when the forward and reverse memory-failure
 * paths need to be tested independently.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosWildcardEaimemory
 * class WildcardEaimemoryTest {
 *   @Test
 *   void applicationDoesNotConfuseNativeMemoryFailureWithJvmOom(ConnectionInfo info) {
 *     // assert that the application emits a DNS-specific error, not an OOM error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaimemory
 * @see ChaosReverseEaimemory
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosWildcardEaimemory.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.WILDCARD, errno = EaiErrno.EAI_MEMORY)
public @interface ChaosWildcardEaimemory {

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
   * @ChaosWildcardEaimemory(id = "primary",  probability = 0.001)
   * @ChaosWildcardEaimemory(id = "replica",  probability = 0.01)
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
    ChaosWildcardEaimemory[] value();
  }
}
