/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Limits the throughput of connections flowing through the proxy to a maximum rate in KB/s.
 *
 * <p>Uses Toxiproxy's {@code bandwidth} toxic. Throttles data transfer per connection independently.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>rateKbps</strong> — max data rate per connection in KB/s (must be &gt; 0).
 *   <li><strong>toxicity</strong> — fraction of connections that are bandwidth-limited.
 * </ul>
 *
 * <h2>Real-World Scenarios</h2>
 *
 * <table>
 *   <caption>Bandwidth values by connection type</caption>
 *   <tr><th>Scenario</th><th>rateKbps</th></tr>
 *   <tr><td>Mobile 2G</td><td>10</td></tr>
 *   <tr><td>Mobile 3G</td><td>100–375</td></tr>
 *   <tr><td>Mobile 4G</td><td>1,000–5,000</td></tr>
 *   <tr><td>DSL</td><td>1,000–10,000</td></tr>
 * </table>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * BandwidthToxic mobile3g = BandwidthToxic.builder()
 *     .name("mobile-3g")
 *     .rateKbps(100)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LatencyToxic for adding delay rather than limiting throughput
 */
public final class BandwidthToxic extends AbstractToxic {

  private final int rateKbps;

  private BandwidthToxic(final Builder builder) {
    super(builder);
    this.rateKbps = builder.rateKbps;
    validatePositive(rateKbps, "rateKbps");
  }

  /** @return new builder (defaults: rateKbps=100, toxicity=1.0) */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String type() {
    return "bandwidth";
  }

  /** Maximum transfer rate per connection in KB/s (&gt; 0). */
  public int rateKbps() {
    return rateKbps;
  }

  /** Returns attributes-only JSON: {@code {"rate":N}}. */
  @Override
  public String toJson() {
    return String.format("{\"rate\":%d}", rateKbps);
  }

  /** Builder for {@link BandwidthToxic}. */
  public static final class Builder extends AbstractBuilder<Builder> {

    private int rateKbps = 100;

    private Builder() {}

    /**
     * Maximum transfer rate per connection (default: 100 KB/s, must be &gt; 0).
     *
     * @param rateKbps rate in kilobytes per second
     * @return this builder
     */
    public Builder rateKbps(final int rateKbps) {
      this.rateKbps = rateKbps;
      return this;
    }

    /**
     * Build the {@link BandwidthToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if rateKbps ≤ 0
     */
    public BandwidthToxic build() {
      return new BandwidthToxic(this);
    }
  }
}
