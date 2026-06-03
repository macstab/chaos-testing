/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Every {@code poll()}/{@code epoll_wait()} call on any socket artificially times out after the
 * configured duration, regardless of actual socket readiness. Applications using event-driven I/O —
 * NIO selectors, Netty event loops, Go's netpoll, Node.js libuv — receive spurious poll timeouts
 * that must be handled correctly. Tests whether the I/O event loop correctly distinguishes real
 * timeouts from readiness events and does not falsely close connections.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.timeout(wildcard, duration, toxicity)} via libchaos-net. Every
 * {@code poll()} / {@code epoll_wait()} call on the container returns timeout (zero ready fds)
 * before the real kernel timeout fires. In production this happens when a hypervisor stall or
 * kernel scheduling hiccup delays the return from {@code epoll_wait()}, or when an Istio sidecar
 * under heavy load holds the connection-ready event longer than the application's configured I/O
 * timeout.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Well-written event loops re-poll on timeout and are unaffected. Applications with absolute I/O
 * timeouts (not deadline-based) may close connections prematurely. NIO applications that treat
 * every {@code select()} timeout as an idle-connection expiry incorrectly close warm connections,
 * triggering unnecessary TCP reconnects under load.
 *
 * <h2>Industry references</h2>
 *
 * <p>Spurious poll timeouts from hypervisor stalls are documented in the AWS EC2 "clock issues"
 * knowledge base article. The Netty event loop timeout semantics and the distinction between read
 * timeout, write timeout, and connection timeout are covered in the Netty 4.x User Guide.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosPollTimeout(timeoutMs = 3000)
 * class MyResilienceTest {
 *
 *   @ServiceHealthCheck
 *   void healthy() { assertThat(service.ping()).isEqualTo("ok"); }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosPollTimeout.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.PollTimeoutComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosPollTimeout {

  /** Poll timeout duration in milliseconds. Defaults to {@code 5000} (5 seconds). */
  long timeoutMs() default 5_000L;

  /**
   * Probability that any given poll call is forced to timeout early. Defaults to {@code 1.0} (all
   * poll calls time out).
   */
  double toxicity() default 1.0;

  /**
   * libchaos-net endpoint selector. Accepted forms:
   * <ul>
   *   <li>{@code "*"} — wildcard; matches every socket (default)</li>
   *   <li>{@code "tcp4://host:port"} — TCP/IPv4 to a specific host and port</li>
   *   <li>{@code "tcp6://[host]:port"} — TCP/IPv6</li>
   *   <li>{@code "udp4://host:port"} — UDP/IPv4</li>
   *   <li>{@code "udp6://[host]:port"} — UDP/IPv6</li>
   *   <li>{@code "unix:///path"} — Unix-domain socket</li>
   *   <li>{@code "dns://hostname"} — DNS interception at {@code getaddrinfo} time</li>
   *   <li>{@code "hostname"} — shorthand for {@code dns://hostname}</li>
   * </ul>
   */
  String endpoint() default "*";

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-net.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosPollTimeout[] value();
  }
}
