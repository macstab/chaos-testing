/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CacheChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * No-op fallback for {@link CacheChaos} — returned when no real implementation is on the classpath.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NoOpCacheChaos implements CacheChaos {

  /** Creates a no-op cache chaos implementation. */
  public NoOpCacheChaos() {}

  @Override
  public void slowResponse(final GenericContainer<?> container, final Duration delay) {
    // No-op
  }

  @Override
  public void injectConnectionFailures(final GenericContainer<?> container, final double rate) {
    // No-op
  }

  @Override
  public void limitThroughput(final GenericContainer<?> container, final long rateKBps) {
    // No-op
  }

  @Override
  public void truncateResponses(final GenericContainer<?> container, final long bytes) {
    // No-op
  }

  @Override
  public void removeFault(final GenericContainer<?> container, final String faultName) {
    // No-op
  }

  @Override
  public void removeAllFaults(final GenericContainer<?> container) {
    // No-op
  }

  @Override
  public void forceEviction(final GenericContainer<?> container, final int percentage) {
    // No-op
  }

  @Override
  public void limitMemory(final GenericContainer<?> container, final long bytes) {
    // No-op
  }

  @Override
  public void setEvictionPolicy(final GenericContainer<?> container, final String policy) {
    // No-op
  }

  @Override
  public void disconnectClients(final GenericContainer<?> container) {
    // No-op
  }

  @Override
  public void flushAll(final GenericContainer<?> container) {
    // No-op
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    // No-op
  }

  @Override
  public int priority() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isSupported() {
    return false;
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    // No-op
  }
}
