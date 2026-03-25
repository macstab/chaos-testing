/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

/**
 * Base interface for type-safe toxic configurations.
 *
 * <p>All toxic configurations must implement this sealed interface to ensure compile-time type
 * safety and prevent invalid toxic attribute combinations.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@link LatencyToxic} - Add latency to connections
 *   <li>{@link TimeoutToxic} - Force connection timeouts
 *   <li>{@link BandwidthToxic} - Limit bandwidth
 *   <li>{@link SlowCloseToxic} - Delay connection close
 *   <li>{@link LimitDataToxic} - Limit total bytes transferred
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public sealed interface ToxicConfig
    permits LatencyToxic,
        TimeoutToxic,
        BandwidthToxic,
        SlowCloseToxic,
        LimitDataToxic,
        LegacyToxicConfig {

  /**
   * Get the toxic name (must be unique per proxy).
   *
   * @return toxic name
   */
  String name();

  /**
   * Get the toxic type identifier.
   *
   * @return toxic type (e.g., "latency", "timeout", "bandwidth")
   */
  String type();

  /**
   * Get the toxicity probability (0.0-1.0).
   *
   * <p>1.0 = always apply toxic, 0.0 = never apply.
   *
   * @return toxicity value between 0.0 and 1.0
   */
  double toxicity();

  /**
   * Serialize toxic attributes to JSON format for Toxiproxy API.
   *
   * @return JSON string containing toxic-specific attributes
   */
  String toJson();
}
