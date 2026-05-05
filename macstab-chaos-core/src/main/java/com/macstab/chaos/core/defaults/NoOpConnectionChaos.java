/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ConnectionChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * No-op {@link ConnectionChaos} — all operations are silent no-ops (connection chaos is always
 * optional). No exception thrown; the interface is used for low-risk decorative effects.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NoOpConnectionChaos implements ConnectionChaos {

  /** Creates a no-op connection chaos implementation. */
  public NoOpConnectionChaos() {}

  /** No-op addLatency implementation. */
  @Override
  public void addLatency(
      final GenericContainer<?> container, final String target, final Duration latency) {
    // No-op
  }

  /** No-op dropPackets implementation. */
  @Override
  public void dropPackets(
      final GenericContainer<?> container, final String target, final double rate) {
    // No-op
  }

  /** No-op limitBandwidth implementation. */
  @Override
  public void limitBandwidth(
      final GenericContainer<?> container, final String target, final long bytesPerSecond) {
    // No-op
  }

  /** No-op timeoutConnections implementation. */
  @Override
  public void timeoutConnections(
      final GenericContainer<?> container, final String target, final Duration timeout) {
    // No-op
  }

  /** No-op slowClose implementation. */
  @Override
  public void slowClose(
      final GenericContainer<?> container, final String target, final Duration delay) {
    // No-op
  }

  /** No-op rejectConnections implementation. */
  @Override
  public void rejectConnections(final GenericContainer<?> container, final String target) {
    // No-op
  }

  /** No-op reset implementation. */
  @Override
  public void reset(final GenericContainer<?> container) {
    // No-op
  }

  /** Always returns false (not supported). */
  @Override
  public int priority() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isSupported() {
    return false;
  }

  /** No-op installTools implementation. */
  @Override
  public void installTools(final GenericContainer<?> container) {
    // No-op
  }
}
