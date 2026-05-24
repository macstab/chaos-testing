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
 * Injects {@code EAI_AGAIN} into every {@code getaddrinfo(3)} call (forward DNS lookup), causing
 * the call to return {@code EAI_AGAIN} as if the name-service resolver reported a temporary
 * failure.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selectorKind = {@code FORWARD}, errno = {@code
 * EAI_AGAIN}) tuple. This annotation always fires on every intercepted forward lookup — there is no
 * per-call probability field. Use it when you need every resolution attempt to fail temporarily so
 * that the application's DNS retry and caching logic is fully exercised. No runtime selector-errno
 * validation is needed.
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
 *       EAI_AGAIN} without performing any real resolver query.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every attempt to resolve a hostname to an address fails with a temporary error; the
 *       application must implement retry with exponential back-off rather than treating the first
 *       failure as fatal.
 *   <li>Connection pools that resolve hostnames on demand will not be able to establish new
 *       connections; idle pool connections that were established before the injection will remain
 *       usable, making this a partial-degradation scenario.
 *   <li>Service-discovery clients (Consul, Kubernetes DNS) that rely on {@code getaddrinfo} for
 *       endpoint resolution will see every lookup fail transiently; assert that the client returns
 *       stale-cache results or activates a circuit breaker rather than propagating the error
 *       immediately to callers.
 *   <li>Assert that the retry budget is finite and that the application emits a structured error
 *       after exhausting retries rather than looping indefinitely.
 * </ul>
 *
 * <p>In production, {@code EAI_AGAIN} from {@code getaddrinfo} occurs during DNS server outages,
 * resolver timeout events, network partitions between the container and its configured nameserver,
 * and during rolling restarts of in-cluster DNS pods (e.g. CoreDNS).
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EAI_AGAIN} is defined by POSIX as "temporary failure in name resolution". The glibc
 * resolver returns it when the upstream DNS server returns {@code SERVFAIL} ({@code RCODE 2}) or
 * when the query times out after all configured retries. Unlike {@code EAI_FAIL}, which signals a
 * permanent authoritative failure, {@code EAI_AGAIN} explicitly allows the caller to retry later.
 *
 * <p>Modern HTTP client libraries (Apache HttpClient, OkHttp, Netty) distinguish between {@code
 * EAI_AGAIN} and {@code EAI_FAIL} in their DNS failure handling: transient failures trigger a retry
 * (possibly against a different resolver), while permanent failures immediately surface as a
 * connection-refused error to the application. Injecting {@code EAI_AGAIN} on every call tests the
 * retry path, not just the single-failure path.
 *
 * <p>Java's {@code InetAddress.getByName()} calls {@code getaddrinfo} internally and maps {@code
 * EAI_AGAIN} to an {@code UnknownHostException} with the message "Temporary failure in name
 * resolution". Application code that catches {@code UnknownHostException} without examining the
 * message will treat transient and permanent DNS failures identically; this injection makes that
 * behavioural assumption visible under test.
 *
 * <p>DNS-aware connection pools (HikariCP with {@code initializationFailTimeout = 0}, Lettuce
 * cluster clients) that resolve endpoints once at startup and cache the result will survive this
 * injection for as long as the cached addresses remain valid. Injecting {@code EAI_AGAIN} before
 * pool initialisation forces the pool to handle failure at the earliest possible moment.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @ChaosForwardEaiagain
 * class ForwardEaiagainTest {
 *   @Test
 *   void applicationRetriesAndExhaustsRetryBudgetGracefully(ConnectionInfo info) {
 *     // assert that the application retries with back-off and emits a structured error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosForwardEaifail
 * @see ChaosWildcardEaiagain
 * @see com.macstab.chaos.dns.annotation.l1.DnsEaiBinding
 */
@Repeatable(ChaosForwardEaiagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsEaiTranslator")
@DnsEaiBinding(selectorKind = DnsSelectorKind.FORWARD, errno = EaiErrno.EAI_AGAIN)
public @interface ChaosForwardEaiagain {

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
   * @ChaosForwardEaiagain(id = "primary",  probability = 0.001)
   * @ChaosForwardEaiagain(id = "replica",  probability = 0.01)
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
    ChaosForwardEaiagain[] value();
  }
}
