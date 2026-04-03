/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DiskChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoOpDiskChaos implements DiskChaos {

  /** Creates a no-op disk chaos implementation. */
  public NoOpDiskChaos() {
    // Default constructor
  }

  private static final String ERROR_MESSAGE =
      "Disk chaos not available. Add: " + ChaosVersion.formatDependency("macstab-chaos-disk");

  @Override
  public void limitWriteBandwidth(final GenericContainer<?> c, final String b) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void limitReadBandwidth(final GenericContainer<?> c, final String b) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void limitReadIOPS(final GenericContainer<?> c, final int i) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void limitWriteIOPS(final GenericContainer<?> c, final int i) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void fillDisk(final GenericContainer<?> c, final String m, final int p) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stressDisk(final GenericContainer<?> c, final int w) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void installTools(final GenericContainer<?> c) {
    // No-op: Real implementation would install required tools
  }

  @Override
  public void reset(final GenericContainer<?> c) {
    // No-op: Real implementation would reset chaos effects
  }

  @Override
  public int priority() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isSupported() {
    return false;
  }
}
