/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.NetworkChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

/**
 * No-op {@link NetworkChaos} returned when {@code macstab-chaos-network} is absent from the
 * classpath. All active operations throw {@link
 * com.macstab.chaos.core.exception.ChaosProviderNotFoundException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NoOpNetworkChaos implements NetworkChaos {

  /** Creates a no-op network chaos implementation. */
  public NoOpNetworkChaos() {
    // Default constructor
  }

  private static final String ERROR_MESSAGE =
      "Network chaos not available. Add: " + ChaosVersion.formatDependency("macstab-chaos-network");

  @Override
  public void injectLatency(GenericContainer<?> c, Duration d) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void injectLatencyWithJitter(GenericContainer<?> c, Duration d, Duration j) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void injectPacketLoss(GenericContainer<?> c, double p) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void injectCorrelatedPacketLoss(GenericContainer<?> c, double p, double cor) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void limitBandwidth(GenericContainer<?> c, String r) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void partitionFrom(GenericContainer<?> c, GenericContainer<?> t) {
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
