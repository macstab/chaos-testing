/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import java.util.Objects;

/**
 * Limits the throughput of connections flowing through the proxy to a maximum rate in KB/s.
 *
 * <p>Uses Toxiproxy's {@code bandwidth} toxic, which throttles the data transfer rate for each
 * affected connection independently. Both upstream and downstream directions are capped at the
 * configured rate.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>rateKbps</strong> — maximum data rate per connection in kilobytes per second.
 *       Applied independently per connection (not shared across all connections to the proxy). Must
 *       be {@code > 0}.
 *   <li><strong>toxicity</strong> — fraction of connections that are bandwidth-limited. {@code 1.0}
 *       limits all connections; {@code 0.5} limits approximately half.
 * </ul>
 *
 * <h2>Real-World Scenarios</h2>
 *
 * <table>
 *   <caption>Bandwidth values by connection type</caption>
 *   <tr><th>Scenario</th><th>rateKbps</th></tr>
 *   <tr><td>Mobile 2G (GPRS)</td><td>10</td></tr>
 *   <tr><td>Mobile 3G</td><td>100–375</td></tr>
 *   <tr><td>Mobile 4G</td><td>1,000–5,000</td></tr>
 *   <tr><td>DSL</td><td>1,000–10,000</td></tr>
 *   <tr><td>Home broadband</td><td>10,000–100,000</td></tr>
 * </table>
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * // Simulate mobile 3G: 100 KB/s on all connections
 * BandwidthToxic mobile3g = BandwidthToxic.builder()
 *     .name("mobile-3g")
 *     .rateKbps(100)
 *     .build();
 *
 * // Simulate flaky DSL: 1 MB/s on 30% of connections
 * BandwidthToxic flakyDsl = BandwidthToxic.builder()
 *     .name("flaky-dsl")
 *     .rateKbps(1000)
 *     .toxicity(0.3)
 *     .build();
 *
 * // Extreme test: near-dial-up speed on all connections
 * BandwidthToxic dialUp = BandwidthToxic.builder()
 *     .name("dial-up")
 *     .rateKbps(5)
 *     .build();
 * }</pre>
 *
 * <h2>Convenience Method</h2>
 *
 * <p>For simple cases, prefer {@link com.macstab.chaos.proxy.ProxyChaosProvider#limitBandwidth}
 * which builds this toxic internally:
 *
 * <pre>{@code
 * chaos.limitBandwidth(container, "redis", 100); // 100 KB/s
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LatencyToxic for adding delay rather than limiting throughput
 * @see LimitDataToxic for closing connections after a byte threshold
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

  /**
   * Create a new builder.
   *
   * <p>Defaults: {@code rateKbps=100}, {@code toxicity=1.0}.
   *
   * @return new builder instance
   */
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

  /**
   * Maximum transfer rate per connection in kilobytes per second.
   *
   * @return rate in KB/s (> 0)
   */
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

  // ==================== Builder ====================

  /**
   * Builder for {@link BandwidthToxic}.
   *
   * <p>Defaults: {@code rateKbps=100}, {@code toxicity=1.0}. Only {@link #name(String)} is
   * required.
   */
  public static final class Builder {

    private String name;
    private int rateKbps = 100;
    private double toxicity = 1.0;

    private Builder() {}

    /**
     * Set the unique name for this toxic within its proxy.
     *
     * @param name unique toxic name (required)
     * @return this builder
     */
    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    /**
     * Set the maximum transfer rate per connection.
     *
     * <p>Default: {@code 100} KB/s (approximate mobile 3G speed). Must be {@code > 0}.
     *
     * @param rateKbps transfer rate in kilobytes per second
     * @return this builder
     * @throws IllegalArgumentException if ≤ 0
     */
    public Builder rateKbps(final int rateKbps) {
      this.rateKbps = rateKbps;
      return this;
    }

    /**
     * Set the fraction of connections this toxic applies to.
     *
     * <p>Default: {@code 1.0} (all connections). Range: [0.0, 1.0].
     *
     * @param toxicity connection fraction (0.0 = none, 1.0 = all)
     * @return this builder
     * @throws IllegalArgumentException if outside [0.0, 1.0]
     */
    public Builder toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return this;
    }

    /**
     * Build the {@link BandwidthToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if any value is out of range
     */
    public BandwidthToxic build() {
      return new BandwidthToxic(this);
    }
  }
}
