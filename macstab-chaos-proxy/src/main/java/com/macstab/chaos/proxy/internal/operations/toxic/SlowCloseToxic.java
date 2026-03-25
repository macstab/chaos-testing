/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Slow close toxic configuration - delays the TCP socket from closing.
 *
 * <p>Simulates scenarios where connections hang during close/shutdown.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SlowCloseToxic implements ToxicConfig {

  private final String name;
  private final int delayMs;
  private final double toxicity;

  private SlowCloseToxic(final Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.delayMs = builder.delayMs;
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
    return "slow_close";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  public int delayMs() {
    return delayMs;
  }

  @Override
  public String toJson() {
    return String.format("{\"delay\":%d}", delayMs);
  }

  private void validate() {
    if (delayMs < 0) {
      throw new IllegalArgumentException("delayMs must be >= 0, got: " + delayMs);
    }
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  public static final class Builder {
    private String name;
    private int delayMs = 0;
    private double toxicity = 1.0;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder delayMs(final int delayMs) {
      this.delayMs = delayMs;
      return this;
    }

    public Builder toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return this;
    }

    public SlowCloseToxic build() {
      return new SlowCloseToxic(this);
    }
  }
}
