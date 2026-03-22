/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.macstab.chaos.core.util.ContainerIdFormatter;

/**
 * Represents the current chaos engineering state of a container.
 *
 * <p><strong>Purpose:</strong> Track what network chaos is currently active on a container for
 * observability and debugging.
 *
 * <p><strong>Thread Safety:</strong> This class is immutable and thread-safe.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * NetworkChaosController chaos = new NetworkChaosController(containers);
 * chaos.injectLatency(redis, Duration.ofMillis(100));
 *
 * Optional<ChaosState> state = chaos.getChaosState(redis);
 * if (state.isPresent()) {
 *     System.out.println("Chaos type: " + state.get().getType());
 *     System.out.println("Applied at: " + state.get().getAppliedAt());
 *     System.out.println("Duration: " + state.get().getDurationSinceApplied());
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
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
   * Gets the chaos type.
   *
   * @return chaos type
   */
  public ChaosType getType() {
    return type;
  }

  /**
   * Gets human-readable details about the chaos.
   *
   * @return details (e.g., "100ms latency", "30.0% packet loss")
   */
  public String getDetails() {
    return details;
  }

  /**
   * Gets when the chaos was applied.
   *
   * @return application timestamp
   */
  public Instant getAppliedAt() {
    return appliedAt;
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
