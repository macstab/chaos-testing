/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoOpCpuChaos implements CpuChaos {

  /** Creates a no-op CPU chaos implementation. */
  public NoOpCpuChaos() {
    // Default constructor
  }

  private static final String ERROR_MESSAGE =
      "CPU chaos not available. Add dependency: "
          + ChaosVersion.formatDependency("macstab-chaos-cpu");

  @Override
  public void throttle(final GenericContainer<?> container, final int percentage) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stress(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stress(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public int getCurrentUsage(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    // No-op: Real implementation would install stress-ng
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    // No-op: Real implementation would reset cgroups + kill stress processes
  }

  @Override
  public boolean isSupported() {
    return false;
  }
}
