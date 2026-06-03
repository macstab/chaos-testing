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
 * <p>Inbound TCP {@code recv()} calls return corrupted data at the configured rate. The
 * application-level protocol interprets the corrupted bytes as a malformed frame or an
 * invalid checksum, causing the connection to be torn down with a TCP RST. Under sustained
 * load this produces a continuous RST storm: connections are established but immediately
 * broken, request queues back up, and retries amplify the effect.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.corrupt(Endpoint.wildcard(), rate, 1.0)} via libchaos-net, which
 * targets the implicit {@code RECV} operation. The {@code rate} parameter controls the fraction
 * of bytes corrupted within each affected recv call; the outer toxicity is fixed at {@code 1.0}
 * so that every recv call is considered. In production this failure mode arises from a
 * middlebox injecting RST packets (e.g. an intrusion-prevention system), from a NIC with
 * CRC errors under thermal stress, or from a virtual switch dropping or mangling frames.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Applications that rely on connection reuse (JDBC pools, HTTP/2 streams) are disproportionately
 * affected because a single corrupted recv tears down the entire connection, not just one
 * request. Reconnection storms follow, compounding the impact. Protocol-level checksums
 * (TLS, HTTP/2 frame headers) will surface errors rapidly, so failures are visible —
 * but they may not be actionable without identifying the corrupting middlebox.
 *
 * <h2>Industry references</h2>
 *
 * <p>TCP RST injection by middleboxes is documented in RIPE NCC reports on internet
 * censorship techniques and in the Comcast BitTorrent throttling case (2007), where
 * Comcast injected RST packets to terminate P2P connections. NIC CRC errors causing
 * silent data corruption are documented in the Linux kernel ethtool diagnostics guide.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosTcpResetStorm
 * class MyResilienceTest {
 *   @ServiceHealthCheck
 *   void healthy() { assertThat(service.ping()).isEqualTo("ok"); }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosTcpResetStorm.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.TcpResetStormComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosTcpResetStorm {

  /**
   * Fraction of bytes corrupted within each affected {@code recv()} call, in
   * {@code (0.0, 1.0]}. Defaults to {@code 0.3} (30% of bytes corrupted per recv).
   */
  double rate() default 0.3;

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
    CompositeChaosTcpResetStorm[] value();
  }
}
