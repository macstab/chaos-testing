/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.util.ChaosVersion;

import lombok.extern.slf4j.Slf4j;

/**
 * No-op {@link CpuChaos} implementation returned when {@code macstab-chaos-cpu} is not on the
 * classpath.
 *
 * <p>All chaos operations throw {@link ChaosProviderNotFoundException}. Lifecycle operations
 * ({@link #installTools}, {@link #reset}, {@link #resetPriority}) are silent no-ops so that cleanup
 * code in {@code @AfterEach} does not fail when the module is absent.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NoOpCpuChaos implements CpuChaos {

  private static final String ERROR_MESSAGE =
      "CPU chaos not available. Add dependency: "
          + ChaosVersion.formatDependency("macstab-chaos-cpu");

  /** Creates a no-op CPU chaos implementation. */
  public NoOpCpuChaos() {
    // Default constructor
  }

  // ==================== Throttling ====================

  @Override
  public void throttle(final GenericContainer<?> container, final int percentage) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void throttle(
      final GenericContainer<?> container, final int percentage, final Duration duration) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  // ==================== CPU Compute Stress ====================

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
  public void stressWithThrottle(
      final GenericContainer<?> container, final int workers, final int percentage) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  // ==================== Cache Stress ====================

  @Override
  public void stressCache(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stressCache(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stressCacheLine(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  // ==================== Scheduler / Context Switch Stress ====================

  @Override
  public void stressContextSwitch(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stressThreadSwitch(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  // ==================== Pipeline / Interrupt Stress ====================

  @Override
  public void stressBranchPredictor(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stressTimerInterrupts(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stressMatrix(final GenericContainer<?> container, final int workers) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void stressMatrix(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  // ==================== CPU Affinity ====================

  @Override
  public void pinToCoreMask(final GenericContainer<?> container, final long affinityMask) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  // ==================== Process Priority ====================

  @Override
  public void degradePriority(final GenericContainer<?> container, final int niceValue) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public void resetPriority(final GenericContainer<?> container) {
    // Silent no-op: safe to call in @AfterEach even when module is absent.
  }

  // ==================== Observability ====================

  @Override
  public int getCurrentUsage(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public int getAvailableCores(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public boolean isThrottled(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public boolean isStressed(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public boolean isAffinityPinned(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public long getPinnedCoreMask(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  @Override
  public int getNiceValue(final GenericContainer<?> container) {
    throw new ChaosProviderNotFoundException(ERROR_MESSAGE);
  }

  // ==================== Lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    // Silent no-op: safe to call in @BeforeEach even when module is absent.
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    // Silent no-op: safe to call in @AfterEach even when module is absent.
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
