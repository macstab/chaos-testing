/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.TimeChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

/**
 * No-op {@link TimeChaos} returned when {@code macstab-chaos-time} is absent from the classpath.
 * All active operations throw {@link
 * com.macstab.chaos.core.exception.ChaosProviderNotFoundException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NoOpTimeChaos implements TimeChaos {

  /** Creates a no-op time chaos implementation. */
  public NoOpTimeChaos() {
    // Default constructor
  }

  private static final String ERROR_MESSAGE =
      "Time chaos not available. Add: " + ChaosVersion.formatDependency("macstab-chaos-time");

  @Override
  public void shift(GenericContainer<?> c, Duration o) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void drift(GenericContainer<?> c, double s) {
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
