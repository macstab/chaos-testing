/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DnsChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoOpDnsChaos implements DnsChaos {

  /** Creates a no-op DNS chaos implementation. */
  public NoOpDnsChaos() {
    // Default constructor
  }

  private static final String ERROR_MESSAGE =
      "DNS chaos not available. Add: " + ChaosVersion.formatDependency("macstab-chaos-dns");

  @Override
  public void blockResolution(GenericContainer<?> c, String h) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void delayResolution(GenericContainer<?> c, Duration d) {
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
  public boolean isSupported() {
    return false;
  }
}
