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
 * Injects {@code EAI_MEMORY} into every {@code getaddrinfo(3)} call (forward DNS lookup), causing
 * the call to return {@code EAI_MEMORY} as if the resolver could not allocate memory to store the
 * response.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code FORWARD}, errno =
 * {@code EAI_MEMORY}) tuple. This annotation always fires on every intercepted forward lookup —
 * there is no per-call probability field. Use it when you need every resolution attempt to fail
 * with an out-of-memory condition so that the application's memory-pressure handling is exercised
 * under a DNS-failure scenario. No runtime selector-errno validation is needed.
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
 *       {@code EAI_MEMORY} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every forward resolution attempt fails with a memory-exhaustion indicator; the application
 *       must not assume that retrying will succeed, since the memory condition may be persistent.
 *   <li>Application code that distinguishes DNS-infrastructure failures from host-memory failures
 *       will emit different diagnostic messages; assert the correct classification is emitted.
 *   <li>Java's {@code InetAddress.getByName()} maps {@code EAI_MEMORY} to an
 *       {@code UnknownHostException}; high-level frameworks that catch {@code OOM} separately will
 *       not intercept this — assert that the DNS path is handled independently.
 *   <li>Assert that the application does not silently swallow {@code EAI_MEMORY} and proceed
 *       with a null or stale address, which would produce a misleading connection error downstream.
 * </ul>
 *
 * <p>In production, {@code EAI_MEMORY} from {@code getaddrinfo} occurs when the process is near
 * its memory limit ({@code RLIMIT_AS} or cgroup memory limit) and cannot allocate the internal
 * buffers that the glibc resolver requires to parse the DNS response and build the
 * {@code addrinfo} linked list.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EAI_MEMORY} is defined by POSIX as "memory allocation failure". The glibc resolver
 * returns it when an internal {@code malloc} call fails during the parsing of a DNS response or
 * the construction of the returned {@code addrinfo} chain. Unlike other {@code EAI_*} codes, this
 * error is entirely host-side — no DNS packet was malformed and no server was unreachable.
 *
 * <p>Because Java's JVM heap is separate from the native heap, a JVM process can be in a healthy
 * heap state while the native heap (used by glibc, libpq, libcurl) is exhausted. This scenario
 * is particularly common in JNI-heavy applications: the JVM garbage-collects its objects but the
 * native libraries' internal buffers fragment the native heap over time. Injecting {@code EAI_MEMORY}
 * tests whether the JNI boundary correctly surfaces this failure to the Java layer.
 *
 * <p>Applications that use asynchronous DNS resolution (e.g. via c-ares) will receive this error
 * in a callback rather than a synchronous return, which requires that the callback correctly handles
 * the {@code EAI_MEMORY} code without assuming a valid address pointer. This injection exercises
 * that callback path without requiring actual memory exhaustion.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosForwardEaimemory
 * class ForwardEaimemoryTest {
 *   @Test
 *   void applicationHandlesMemoryExhaustionDuringDnsResolution(ConnectionInfo info) {
 *     // assert that a structured error is emitted and no null-address dereference occurs
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaiagain
 * @see ChaosForwardEaifail
 * @see ChaosWildcardEaimemory
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosForwardEaimemory.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.FORWARD, errno = EaiErrno.EAI_MEMORY)
public @interface ChaosForwardEaimemory {

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
   * @ChaosForwardEaimemory(id = "primary",  probability = 0.001)
   * @ChaosForwardEaimemory(id = "replica",  probability = 0.01)
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
    ChaosForwardEaimemory[] value();
  }
}
