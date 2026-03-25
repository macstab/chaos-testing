/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Timeout toxic configuration - stops all data from getting through and closes the connection after
 * a timeout.
 *
 * <p>Simulates complete connection hangs and forced timeouts.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TimeoutToxic implements ToxicConfig {

  private final String name;
  private final int timeoutMs;
  private final double toxicity;

  private TimeoutToxic(final Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.timeoutMs = builder.timeoutMs;
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
    return "timeout";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  public int timeoutMs() {
    return timeoutMs;
  }

  @Override
  public String toJson() {
    return String.format("{\"timeout\":%d}", timeoutMs);
  }

  private void validate() {
    if (timeoutMs < 0) {
      throw new IllegalArgumentException("timeoutMs must be >= 0, got: " + timeoutMs);
    }
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  public static final class Builder {
    private String name;
    private int timeoutMs = 0;
    private double toxicity = 1.0;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder timeoutMs(final int timeoutMs) {
      this.timeoutMs = timeoutMs;
      return this;
    }

    public Builder toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return this;
    }

    public TimeoutToxic build() {
      return new TimeoutToxic(this);
    }
  }
}
