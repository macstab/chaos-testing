/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CacheChaos;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoOpCacheChaos implements CacheChaos {

  /** No-op injectMisses implementation. */
  @Override
  public void injectMisses(
      final GenericContainer<?> container, final String keyPattern, final double rate) {
    // No-op
  }

  /** No-op slowResponse implementation. */
  @Override
  public void slowResponse(final GenericContainer<?> container, final Duration delay) {
    // No-op
  }

  /** No-op forceEviction implementation. */
  @Override
  public void forceEviction(final GenericContainer<?> container, final int percentage) {
    // No-op
  }

  /** No-op reset implementation. */
  @Override
  public void reset(final GenericContainer<?> container) {
    // No-op
  }

  /** Always returns false (not supported). */
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
