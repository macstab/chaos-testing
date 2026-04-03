/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.FilesystemChaos;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoOpFilesystemChaos implements FilesystemChaos {

  /** No-op fillDisk implementation. */
  @Override
  public void fillDisk(final GenericContainer<?> container, final String size) {
    // No-op
  }

  /** No-op injectPermissionErrors implementation. */
  @Override
  public void injectPermissionErrors(
      final GenericContainer<?> container, final String path, final double rate) {
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
