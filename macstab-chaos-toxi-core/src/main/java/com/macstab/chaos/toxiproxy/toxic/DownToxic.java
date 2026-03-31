/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import java.util.Objects;

/**
 * Stops all data from flowing through the proxy, simulating a completely down upstream.
 *
 * <p>Uses Toxiproxy's internal proxy disable mechanism. When active, all new connections are
 * refused and existing connections are closed. Unlike {@link TimeoutToxic}, there is no delay — the
 * connection is refused immediately.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>toxicity</strong> — fraction of connections that are dropped. {@code 1.0} drops all
 *       connections; {@code 0.3} drops approximately 30%.
 * </ul>
 *
 * <h2>Real-World Scenarios</h2>
 *
 * <table>
 *   <caption>Down toxic configurations by scenario</caption>
 *   <tr><th>Scenario</th><th>toxicity</th></tr>
 *   <tr><td>Complete upstream outage</td><td>1.0</td></tr>
 *   <tr><td>Intermittent packet loss (30%)</td><td>0.3</td></tr>
 *   <tr><td>Occasional drops (5%)</td><td>0.05</td></tr>
 * </table>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class DownToxic implements ToxicConfig {

  private final String name;
  private final double toxicity;

  private DownToxic(final String name, final double toxicity) {
    this.name = Objects.requireNonNull(name, "name");
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
    this.toxicity = toxicity;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String type() {
    return "down";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  @Override
  public String toJson() {
    return String.format(
        "{\"name\":\"%s\",\"type\":\"down\",\"toxicity\":%.2f,\"attributes\":{}}", name, toxicity);
  }

  /** Creates a builder for {@link DownToxic}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link DownToxic}. */
  public static final class Builder {
    private String name = "down";
    private double toxicity = 1.0;

    private Builder() {}

    public Builder name(final String name) {
      this.name = Objects.requireNonNull(name, "name");
      return this;
    }

    public Builder toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return this;
    }

    public DownToxic build() {
      return new DownToxic(name, toxicity);
    }
  }
}
