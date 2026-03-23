/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.macstab.chaos.core.util.ContainerIdFormatter;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public final class ChaosState {

  private final ChaosType type;
  private final String details;
  private final Instant appliedAt;

  /**
   * Creates a chaos state.
   *
   * @param type chaos type
   * @param details human-readable details (e.g., "100ms latency")
   * @param appliedAt when chaos was applied
   */
  private ChaosState(final ChaosType type, final String details, final Instant appliedAt) {
    this.type = Objects.requireNonNull(type, "type");
    this.details = Objects.requireNonNull(details, "details");
    this.appliedAt = Objects.requireNonNull(appliedAt, "appliedAt");
  }

  /**
   * Creates a latency chaos state.
   *
   * @param latency latency amount
   * @return chaos state
   */
  public static ChaosState latency(final Duration latency) {
    return new ChaosState(ChaosType.LATENCY, latency.toMillis() + "ms latency", Instant.now());
  }

  /**
   * Creates a packet loss chaos state.
   *
   * @param lossPercentage loss percentage (0.0 to 1.0)
   * @return chaos state
   */
  public static ChaosState packetLoss(final double lossPercentage) {
    return new ChaosState(
        ChaosType.PACKET_LOSS,
        String.format("%.1f%% packet loss", lossPercentage * 100),
        Instant.now());
  }

  /**
   * Creates a jitter chaos state.
   *
   * @param baseLatency base latency
   * @param jitter jitter amount
   * @return chaos state
   */
  public static ChaosState jitter(final Duration baseLatency, final Duration jitter) {
    return new ChaosState(
        ChaosType.JITTER,
        String.format("%dms ±%dms jitter", baseLatency.toMillis(), jitter.toMillis()),
        Instant.now());
  }

  /**
   * Creates a partition chaos state.
   *
   * @param targetContainerId ID of partitioned container
   * @return chaos state
   */
  public static ChaosState partition(final String targetContainerId) {
    return new ChaosState(
        ChaosType.PARTITION,
        "Partitioned from " + ContainerIdFormatter.truncate(targetContainerId),
        Instant.now());
  }

  /**
   * Gets how long the chaos has been active.
   *
   * @return duration since chaos was applied
   */
  public Duration getDurationSinceApplied() {
    return Duration.between(appliedAt, Instant.now());
  }

  @Override
  public String toString() {
    return String.format(
        "ChaosState{type=%s, details='%s', appliedAt=%s, active=%s}",
        type, details, appliedAt, getDurationSinceApplied());
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final ChaosState that = (ChaosState) o;
    return type == that.type && Objects.equals(details, that.details);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, details);
  }

  /** Types of network chaos. */
  public enum ChaosType {
    /** Network latency injection. */
    LATENCY,

    /** Packet loss injection. */
    PACKET_LOSS,

    /** Network jitter injection. */
    JITTER,

    /** Network partition. */
    PARTITION
  }
}
