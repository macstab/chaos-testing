/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.libchaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.api.AdvancedConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.spi.ConnectionChaosStrategy;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

import lombok.extern.slf4j.Slf4j;

/**
 * Syscall-level connection-chaos strategy backed by {@code libchaos-net} via {@link
 * LibchaosTransport}.
 *
 * <p>Implements both {@link ConnectionChaosStrategy} (so it composes with Toxiproxy) and {@link
 * AdvancedConnectionChaos} (so it surfaces libchaos-net's full capability set). Pre-flight
 * preparation must happen before {@code container.start()} — see {@link LibchaosTransport#prepare};
 * this strategy does not start anything itself.
 *
 * <p><strong>Routing decisions</strong> live in the composite, not here. This class translates
 * portable verbs into libchaos-net rules ({@code addLatency} → bidirectional {@link
 * NetOperation#SEND}+{@link NetOperation#RECV} latency; {@code rejectConnections} → {@link
 * Errno#ECONNREFUSED} on {@link NetOperation#CONNECT}; …). {@link #limitBandwidth} throws {@link
 * ChaosUnsupportedOperationException} so the composite can route bandwidth shaping to Toxiproxy.
 *
 * <p><strong>Thread-safety:</strong> safe for concurrent use across containers; per-container
 * mutation goes through {@link RuleRegistry}'s concurrent maps and {@code LibchaosTransport}'s
 * shell-serialised exec.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class LibchaosNetConnectionChaos
    implements AdvancedConnectionChaos, ConnectionChaosStrategy {

  private static final String TOXIC_LATENCY = "latency";
  private static final String TOXIC_DOWN = "down";
  private static final String TOXIC_TIMEOUT = "timeout";
  private static final String TOXIC_SLOW_CLOSE = "slow_close";

  private final LibchaosTransport transport;
  private final RuleRegistry registry;
  private final AtomicLong ownerCounter;

  /** Default constructor — uses {@code LibchaosLib.NET}. */
  public LibchaosNetConnectionChaos() {
    this(new LibchaosTransport(LibchaosLib.NET));
  }

  /**
   * Package-private for testing.
   *
   * @param transport pre-configured transport (typically a mock)
   */
  LibchaosNetConnectionChaos(final LibchaosTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.registry = new RuleRegistry();
    this.ownerCounter = new AtomicLong(0L);
  }

  // ==================== ChaosProvider ====================

  @Override
  public boolean isSupported() {
    return true;
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    // No-op: libchaos-net is installed pre-start via LibchaosTransport.prepare(),
    // not at runtime via package install.
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      return;
    }
    removeAll(container);
  }

  // ==================== ConnectionChaosStrategy ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    try {
      return transport.isActive(container);
    } catch (final RuntimeException ex) {
      log.debug("isActive probe failed; reporting unsupported", ex);
      return false;
    }
  }

  // ==================== ConnectionChaos (portable verbs) ====================

  @Override
  public void addLatency(
      final GenericContainer<?> container, final String target, final Duration latency) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(latency, "latency must not be null");
    requirePrepared(container);
    final Endpoint ep = parseTcpTarget(target);
    applyTagged(
        container,
        TOXIC_LATENCY,
        List.of(
            NetRule.latency(ep, NetOperation.SEND, latency, 1.0),
            NetRule.latency(ep, NetOperation.RECV, latency, 1.0)));
  }

  @Override
  public void dropPackets(
      final GenericContainer<?> container, final String target, final double rate) {
    Objects.requireNonNull(target, "target must not be null");
    requirePrepared(container);
    if (Double.isNaN(rate) || rate <= 0.0 || rate > 1.0) {
      throw new IllegalArgumentException("rate must be in (0.0, 1.0], got: " + rate);
    }
    final Endpoint ep = parseTcpTarget(target);
    applyTagged(
        container,
        TOXIC_DOWN,
        List.of(NetRule.errno(ep, NetOperation.RECV, Errno.ECONNRESET, rate)));
  }

  @Override
  public void limitBandwidth(
      final GenericContainer<?> container, final String target, final long bytesPerSecond) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-net cannot model bandwidth shaping; the composite must route "
            + "limitBandwidth() to the Toxiproxy strategy.");
  }

  @Override
  public void timeoutConnections(
      final GenericContainer<?> container, final String target, final Duration timeout) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    requirePrepared(container);
    final Endpoint ep = parseTcpTarget(target);
    applyTagged(
        container,
        TOXIC_TIMEOUT,
        List.of(NetRule.errno(ep, NetOperation.CONNECT, Errno.ETIMEDOUT, 1.0)));
  }

  @Override
  public void slowClose(
      final GenericContainer<?> container, final String target, final Duration delay) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(delay, "delay must not be null");
    requirePrepared(container);
    final Endpoint ep = parseTcpTarget(target);
    applyTagged(
        container,
        TOXIC_SLOW_CLOSE,
        List.of(NetRule.latency(ep, NetOperation.SHUTDOWN, delay, 1.0)));
  }

  @Override
  public void rejectConnections(final GenericContainer<?> container, final String target) {
    Objects.requireNonNull(target, "target must not be null");
    requirePrepared(container);
    final Endpoint ep = parseTcpTarget(target);
    applyTagged(
        container,
        TOXIC_DOWN,
        List.of(NetRule.errno(ep, NetOperation.CONNECT, Errno.ECONNREFUSED, 1.0)));
  }

  @Override
  public void removeToxic(
      final GenericContainer<?> container, final String target, final String toxicName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    if (!transport.isActive(container)) {
      return; // strategy not active on this container — composite fan-out covers
    }
    final String selectorPrefix = "tcp4://" + target.toLowerCase();
    for (final RuleRegistry.Entry e : registry.snapshot(container)) {
      if (toxicName.equals(e.toxicName())
          && e.rule().endpoint().toSelector().startsWith(selectorPrefix)) {
        removeEntry(container, e);
      }
    }
  }

  @Override
  public void removeAllToxics(final GenericContainer<?> container, final String target) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    if (!transport.isActive(container)) {
      return;
    }
    final String selectorPrefix = "tcp4://" + target.toLowerCase();
    for (final RuleRegistry.Entry e : registry.snapshot(container)) {
      if (e.toxicName() != null && e.rule().endpoint().toSelector().startsWith(selectorPrefix)) {
        removeEntry(container, e);
      }
    }
  }

  // ==================== AdvancedConnectionChaos: generic ====================

  @Override
  public RuleHandle apply(final GenericContainer<?> container, final NetRule rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    requirePrepared(container);
    return applySingle(container, rule, null);
  }

  @Override
  public List<RuleHandle> applyAll(final GenericContainer<?> container, final List<NetRule> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    rules.forEach(r -> Objects.requireNonNull(r, "rule must not be null"));
    requirePrepared(container);
    final List<RuleHandle> handles = new ArrayList<>(rules.size());
    for (final NetRule r : rules) {
      handles.add(applySingle(container, r, null));
    }
    return handles;
  }

  @Override
  public void remove(final GenericContainer<?> container, final RuleHandle handle) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(handle, "handle must not be null");
    requirePrepared(container);
    registry
        .remove(container, handle)
        .ifPresent(entry -> transport.removeRules(container, entry.handle().owner()));
  }

  @Override
  public void removeAll(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    requirePrepared(container);
    for (final RuleRegistry.Entry e : registry.removeAll(container)) {
      try {
        transport.removeRules(container, e.handle().owner());
      } catch (final ChaosOperationFailedException ex) {
        log.warn("Failed to remove rule {} during removeAll; continuing", e.handle().owner(), ex);
      }
    }
  }

  // ==================== AdvancedConnectionChaos: convenience ====================

  @Override
  public RuleHandle failDnsResolve(
      final GenericContainer<?> container,
      final String hostname,
      final Errno errno,
      final double toxicity) {
    return apply(
        container, NetRule.errno(Endpoint.dns(hostname), NetOperation.CONNECT, errno, toxicity));
  }

  @Override
  public RuleHandle corruptRecv(
      final GenericContainer<?> container,
      final Endpoint endpoint,
      final double rate,
      final double toxicity) {
    return apply(container, NetRule.corrupt(endpoint, rate, toxicity));
  }

  @Override
  public RuleHandle forcePollTimeout(
      final GenericContainer<?> container,
      final Endpoint endpoint,
      final Duration timeout,
      final double toxicity) {
    return apply(container, NetRule.timeout(endpoint, timeout, toxicity));
  }

  @Override
  public RuleHandle failUdp(
      final GenericContainer<?> container,
      final String host,
      final int port,
      final NetOperation operation,
      final Errno errno,
      final double toxicity) {
    return apply(container, NetRule.errno(Endpoint.udp4(host, port), operation, errno, toxicity));
  }

  @Override
  public RuleHandle refuseUnix(
      final GenericContainer<?> container,
      final String path,
      final Errno errno,
      final double toxicity) {
    return apply(
        container, NetRule.errno(Endpoint.unix(path), NetOperation.CONNECT, errno, toxicity));
  }

  @Override
  public RuleHandle failListen(
      final GenericContainer<?> container,
      final Endpoint endpoint,
      final Errno errno,
      final double toxicity) {
    return apply(container, NetRule.errno(endpoint, NetOperation.LISTEN, errno, toxicity));
  }

  @Override
  public RuleHandle failAccept(
      final GenericContainer<?> container,
      final Endpoint endpoint,
      final Errno errno,
      final double toxicity) {
    return apply(container, NetRule.errno(endpoint, NetOperation.ACCEPT, errno, toxicity));
  }

  @Override
  public RuleHandle exhaustFds(final GenericContainer<?> container, final double toxicity) {
    return apply(
        container, NetRule.errno(Endpoint.wildcard(), NetOperation.SOCKET, Errno.EMFILE, toxicity));
  }

  // ==================== Internal helpers ====================

  private RuleHandle applySingle(
      final GenericContainer<?> container, final NetRule rule, final String toxicName) {
    final RuleHandle handle = new RuleHandle(nextOwner());
    transport.addRule(container, handle.owner(), NetRuleSerializer.serialize(rule));
    registry.register(container, new RuleRegistry.Entry(handle, rule, toxicName));
    return handle;
  }

  private void applyTagged(
      final GenericContainer<?> container, final String toxicName, final List<NetRule> rules) {
    for (final NetRule r : rules) {
      applySingle(container, r, toxicName);
    }
  }

  private void removeEntry(final GenericContainer<?> container, final RuleRegistry.Entry entry) {
    registry
        .remove(container, entry.handle())
        .ifPresent(removed -> transport.removeRules(container, removed.handle().owner()));
  }

  private String nextOwner() {
    return "r" + ownerCounter.incrementAndGet();
  }

  private void requirePrepared(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      throw new LibchaosNotPreparedException(LibchaosLib.NET.getShortName(), container);
    }
  }

  private static Endpoint parseTcpTarget(final String target) {
    final int idx = target.lastIndexOf(':');
    if (idx <= 0 || idx == target.length() - 1) {
      throw new IllegalArgumentException(
          "target must be in 'host:port' form, got: '" + target + "'");
    }
    final String host = target.substring(0, idx);
    final String portStr = target.substring(idx + 1);
    final int port;
    try {
      port = Integer.parseInt(portStr);
    } catch (final NumberFormatException ex) {
      throw new IllegalArgumentException("port is not a number in target: " + target, ex);
    }
    return Endpoint.tcp4(host, port);
  }
}
