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
 * <p>Send calls fail with {@code ENOBUFS} — no kernel buffer space is available for outbound
 * packets. Applications must handle send failures gracefully: either retry with backoff, propagate
 * an error to the caller, or use non-blocking I/O with write-readiness polling. Tests the send path
 * error handling that is typically invisible during normal operation.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(wildcard, SEND, ENOBUFS, toxicity)} via libchaos-net. In
 * production this happens when a high-speed NIC's transmit ring is full (common during burst
 * traffic on AWS Nitro instances with large MTU), when a container's network namespace is under
 * backpressure from a congested upstream, or when the system is under heavy memory pressure and
 * the kernel cannot allocate socket buffers.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Transient — well-written applications retry. Blocking I/O callers get a {@code SocketException};
 * non-blocking I/O callers must handle {@code ENOBUFS} as distinct from {@code EAGAIN} (would
 * block) vs {@code ENOMEM} (OOM). Applications that treat all send errors as fatal close the
 * connection unnecessarily, amplifying a transient event into a cascading reconnect storm.
 *
 * <h2>Industry references</h2>
 *
 * <p>ENOBUFS on send is documented in the Linux kernel networking FAQ and in AWS EC2 instance
 * networking performance documentation. The DPDK and netmap high-performance networking frameworks
 * include specific ENOBUFS handling paths. FreeBSD's setsockopt(SO_SNDBUF) documentation covers
 * the buffer exhaustion model.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosSendBufferStarvation(toxicity = 0.5)
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
@Repeatable(CompositeChaosSendBufferStarvation.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.SendBufferStarvationComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosSendBufferStarvation {

  /**
   * Probability that any given send call fails with {@code ENOBUFS}. Defaults to {@code 0.5} —
   * intermittent failure to test retry and backoff logic.
   */
  double toxicity() default 0.5;

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
    CompositeChaosSendBufferStarvation[] value();
  }
}
