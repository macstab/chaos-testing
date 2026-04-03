/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ProcessChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.model.ProcessInfo;
import com.macstab.chaos.core.model.Signal;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoOpProcessChaos implements ProcessChaos {

  /** Creates a no-op process chaos implementation. */
  public NoOpProcessChaos() {
    // Default constructor
  }

  private static final String ERROR_MESSAGE =
      "Process chaos not available. Add: " + ChaosVersion.formatDependency("macstab-chaos-process");

  @Override
  public void kill(GenericContainer<?> c, String p, Signal s) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void pause(GenericContainer<?> c, String p, Duration d) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void limitProcesses(GenericContainer<?> c, int m) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public List<ProcessInfo> listProcesses(GenericContainer<?> c) {
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
