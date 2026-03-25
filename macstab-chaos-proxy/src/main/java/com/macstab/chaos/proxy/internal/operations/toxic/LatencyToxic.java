/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Latency toxic configuration - adds latency to all data going through the proxy.
 *
 * <p>Simulates network latency and jitter for testing application resilience to slow networks.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * LatencyToxic toxic = LatencyToxic.builder()
 *     .name("slow-network")
 *     .latencyMs(1000)      // 1 second base latency
 *     .jitterMs(500)        // ±500ms random jitter
 *     .toxicity(1.0)        // Apply to all connections
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class LatencyToxic implements ToxicConfig {

  private final String name;
  private final int latencyMs;
  private final int jitterMs;
  private final double toxicity;

  private LatencyToxic(final Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.latencyMs = builder.latencyMs;
    this.jitterMs = builder.jitterMs;
    this.toxicity = builder.toxicity;

    validateLatency(latencyMs);
    validateJitter(jitterMs);
    validateToxicity(toxicity);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String type() {
    return "latency";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  public int latencyMs() {
    return latencyMs;
  }

  public int jitterMs() {
    return jitterMs;
  }

  @Override
  public String toJson() {
    return String.format("{\"latency\":%d,\"jitter\":%d}", latencyMs, jitterMs);
  }

  // ==================== Validation ====================

  private void validateLatency(final int latency) {
    if (latency < 0) {
      throw new IllegalArgumentException("latencyMs must be >= 0, got: " + latency);
    }
  }

  private void validateJitter(final int jitter) {
    if (jitter < 0) {
      throw new IllegalArgumentException("jitterMs must be >= 0, got: " + jitter);
    }
  }

  private void validateToxicity(final double toxic) {
    if (toxic < 0.0 || toxic > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxic));
    }
  }

  // ==================== Builder ====================

  public static final class Builder {
    private String name;
    private int latencyMs = 0;
    private int jitterMs = 0;
    private double toxicity = 1.0;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder latencyMs(final int latencyMs) {
      this.latencyMs = latencyMs;
      return this;
    }

    public Builder jitterMs(final int jitterMs) {
      this.jitterMs = jitterMs;
      return this;
    }

    public Builder toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return this;
    }

    public LatencyToxic build() {
      return new LatencyToxic(this);
    }
  }
}
