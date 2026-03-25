/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Limit data toxic configuration - closes connection after transmitting a certain number of bytes.
 *
 * <p>Simulates scenarios where connections are severed after partial data transfer.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class LimitDataToxic implements ToxicConfig {

  private final String name;
  private final long bytes;
  private final double toxicity;

  private LimitDataToxic(final Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.bytes = builder.bytes;
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
    return "limit_data";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  public long bytes() {
    return bytes;
  }

  @Override
  public String toJson() {
    return String.format("{\"bytes\":%d}", bytes);
  }

  private void validate() {
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got: " + bytes);
    }
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  public static final class Builder {
    private String name;
    private long bytes = 0;
    private double toxicity = 1.0;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder bytes(final long bytes) {
      this.bytes = bytes;
      return this;
    }

    public Builder toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return this;
    }

    public LimitDataToxic build() {
      return new LimitDataToxic(this);
    }
  }
}
