/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Bandwidth toxic configuration - limits the bandwidth of connections.
 *
 * <p>Simulates slow network connections with limited throughput (in KB/s).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class BandwidthToxic implements ToxicConfig {

  private final String name;
  private final int rateKbps;
  private final double toxicity;

  private BandwidthToxic(final Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.rateKbps = builder.rateKbps;
    this.toxicity = builder.toxicity;

    validate();
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
    return "bandwidth";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  public int rateKbps() {
    return rateKbps;
  }

  @Override
  public String toJson() {
    return String.format("{\"rate\":%d}", rateKbps);
  }

  private void validate() {
    if (rateKbps <= 0) {
      throw new IllegalArgumentException("rateKbps must be > 0, got: " + rateKbps);
    }
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  public static final class Builder {
    private String name;
    private int rateKbps = 100;
    private double toxicity = 1.0;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder rateKbps(final int rateKbps) {
      this.rateKbps = rateKbps;
      return this;
    }

    public Builder toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return this;
    }

    public BandwidthToxic build() {
      return new BandwidthToxic(this);
    }
  }
}
