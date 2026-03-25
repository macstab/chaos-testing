/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Legacy toxic configuration wrapper for backward compatibility.
 *
 * <p>Wraps string-based toxic attributes (JSON) into the ToxicConfig interface. Used internally to
 * bridge old API (loose strings) to new API (type-safe configs).
 *
 * <p><strong>Internal use only</strong> - new code should use type-safe toxic configs (e.g.,
 * LatencyToxic).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class LegacyToxicConfig implements ToxicConfig {

  private final String name;
  private final String type;
  private final String attributesJson;
  private final double toxicity;

  /**
   * Create legacy toxic config.
   *
   * @param name toxic name
   * @param type toxic type
   * @param attributesJson toxic attributes as JSON string
   * @param toxicity toxicity probability (0.0-1.0)
   */
  public LegacyToxicConfig(
      final String name, final String type, final String attributesJson, final double toxicity) {

    this.name = Objects.requireNonNull(name, "name must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.attributesJson = attributesJson != null ? attributesJson : "{}";
    this.toxicity = toxicity;

    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String type() {
    return type;
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  @Override
  public String toJson() {
    return attributesJson;
  }
}
