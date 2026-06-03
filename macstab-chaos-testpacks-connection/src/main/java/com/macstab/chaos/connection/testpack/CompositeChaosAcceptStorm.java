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
 * <p>{@code accept()} calls fail with {@code EMFILE} (per-process file descriptor limit reached) at
 * the configured rate. Listener threads that call {@code accept()} in a loop receive sporadic errors
 * and must decide whether to retry, backoff, or terminate. Tests the server-side loop's resilience
 * to accept failures — a code path that is almost never executed in unit tests because it requires
 * the OS fd limit to actually be hit.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(wildcard, ACCEPT, EMFILE, toxicity)} via libchaos-net. In
 * production this happens when a server process leaks file descriptors — each leaked fd reduces the
 * headroom until {@code accept()} starts failing for new incoming connections. Also triggered by a
 * sudden traffic spike against a server with a conservative {@code ulimit -n} setting.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Inbound connections are dropped at the OS backlog. Clients see connection timeouts or resets. On
 * many servers the accept loop logs an error and continues, but servers with buggy error handling
 * spin-loop on {@code EMFILE}, burning 100% CPU. Servers that exit on unexpected errors cause an
 * unplanned restart.
 *
 * <h2>Industry references</h2>
 *
 * <p>EMFILE in accept loops is described in the NGINX architecture blog and in the Go runtime
 * source (netpoll accept error handling). The fd-leak → EMFILE cascade is covered in the Linux
 * kernel networking documentation and in JDK NIO's {@code EPoll.accept()} implementation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosAcceptStorm(toxicity = 0.8)
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
@Repeatable(CompositeChaosAcceptStorm.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.AcceptStormComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosAcceptStorm {

  /**
   * Probability that any given {@code accept()} call fails with {@code EMFILE}. Defaults to
   * {@code 0.8} — high-rate failure to test accept-loop retry handling.
   */
  double toxicity() default 0.8;

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
    CompositeChaosAcceptStorm[] value();
  }
}
