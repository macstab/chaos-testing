/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.MemoryChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.model.MemoryPressureInfo;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

/**
 * No-op {@link MemoryChaos} returned when {@code macstab-chaos-memory} is absent from the
 * classpath. All active operations throw {@link
 * com.macstab.chaos.core.exception.ChaosProviderNotFoundException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NoOpMemoryChaos implements MemoryChaos {

  /** Creates a no-op memory chaos implementation. */
  public NoOpMemoryChaos() {
    // Default constructor
  }

  private static final String ERROR_MESSAGE =
      "Memory chaos not available. Add: " + ChaosVersion.formatDependency("macstab-chaos-memory");

  @Override
  public void setLimit(GenericContainer<?> c, String l) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void setPressure(GenericContainer<?> c, String t) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stress(GenericContainer<?> c, String s) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public long getCurrentUsage(GenericContainer<?> c) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public MemoryPressureInfo getPressure(GenericContainer<?> c) {
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
