/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Throttles the data transfer rate of connections flowing through the proxy to a configurable
 * maximum in kilobytes per second.
 *
 * <p>Uses Toxiproxy's {@code bandwidth} toxic. Toxiproxy applies the rate limit per connection
 * independently using a token bucket algorithm. Each affected connection gets its own token bucket
 * refilled at {@code rateKbps} KB/s — the rate is NOT shared across all connections to the proxy.
 * Twenty concurrent connections with {@code rateKbps=100} each get 100 KB/s, not 5 KB/s each.
 *
 * <h2>Both Directions Affected</h2>
 *
 * <p>The bandwidth limit applies to both upstream (client → service) and downstream (service →
 * client) traffic. For read-heavy workloads (large Redis HGETALL, large DB result sets), the
 * downstream limit dominates perceived latency. For write-heavy workloads (bulk inserts, large
 * message payloads), the upstream limit dominates.
 *
 * <h2>Attributes JSON</h2>
 *
 * <p>{@link #toJson()} returns {@code {"rate":{rateKbps}}}. The field is named {@code "rate"}, not
 * {@code "rateKbps"} — Toxiproxy's attribute schema uses the short name.
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li>Test streaming behavior under constrained bandwidth (pagination, chunked transfers)
 *   <li>Verify read timeouts trigger when large responses take too long to transfer
 *   <li>Simulate mobile/satellite network conditions for embedded/IoT service testing
 *   <li>Expose buffering inefficiencies in protocol clients
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

  /**
   * @return new builder (defaults: rateKbps=100, toxicity=1.0)
   */
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
