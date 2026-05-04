/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DiskChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.syscall.DiskErrno;
import com.macstab.chaos.core.syscall.DiskOperation;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoOpDiskChaos implements DiskChaos {

  public NoOpDiskChaos() {}

  private static final String NOT_AVAILABLE =
      "Disk chaos not available. Add: " + ChaosVersion.formatDependency("macstab-chaos-disk");

  @Override
  public void stressDisk(final GenericContainer<?> c, final int w) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void stressDisk(final GenericContainer<?> c, final int w, final Duration d) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void fillDisk(final GenericContainer<?> c, final String m, final int p) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void fillDiskBySize(final GenericContainer<?> c, final String m, final String s) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void prepareForFaultInjection(final GenericContainer<?> c) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void resetFaultInjection(final GenericContainer<?> c) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public boolean isFaultInjectionActive(final GenericContainer<?> c) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void injectIOError(
      final GenericContainer<?> c, final String p,
      final DiskOperation op, final DiskErrno e, final double pr) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void injectIOLatency(
      final GenericContainer<?> c, final String p, final DiskOperation op, final Duration l) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void injectTornWrite(final GenericContainer<?> c, final String p, final double pr) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void injectCorruptRead(final GenericContainer<?> c, final String p, final double pr) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public int getDiskUsagePercent(final GenericContainer<?> c, final String m) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public boolean isStressed(final GenericContainer<?> c) {
    throw new ChaosProviderNotFoundException(NOT_AVAILABLE);
  }

  @Override
  public void installTools(final GenericContainer<?> c) {}

  @Override
  public void reset(final GenericContainer<?> c) {}

  @Override
  public int priority() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isSupported() {
    return false;
  }
}
