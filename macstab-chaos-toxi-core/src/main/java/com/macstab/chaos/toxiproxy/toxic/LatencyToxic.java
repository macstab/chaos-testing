/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import lombok.NonNull;

/**
 * Adds fixed latency and optional random jitter to every data chunk flowing through the proxy.
 *
 * <p>Uses Toxiproxy's {@code latency} toxic. Toxiproxy applies the delay to each data chunk
 * individually as it passes through the proxy, in both upstream and downstream directions.
 * This means: a single round-trip (request + response) experiences latency applied twice —
 * once when the request bytes pass from client to upstream, and once when response bytes pass
 * from upstream to client. Tests relying on wall-clock round-trip time should expect
 * approximately {@code 2 × latencyMs} increase per request, not {@code 1 ×}.
 *
 * <h2>Jitter Semantics</h2>
 *
 * <p>When {@code jitterMs > 0}, Toxiproxy adds a uniformly distributed random value in
 * {@code [-jitterMs, +jitterMs]} to each chunk's delay. This models real-world network
 * variability (WiFi, mobile, congested links) more accurately than fixed delay. The actual
 * per-chunk delay is therefore {@code latencyMs + U(-jitterMs, +jitterMs)}, where {@code U}
 * denotes a uniform distribution. Setting {@code latencyMs=0, jitterMs=20} produces pure
 * jitter with no baseline delay.
 *
 * <h2>Attributes JSON</h2>
 *
 * <p>{@link #toJson()} returns {@code {"latency":{latencyMs},"jitter":{jitterMs}}}. This
 * matches Toxiproxy's {@code latency} toxic attribute schema. The field name is {@code "latency"}
 * (not {@code "latencyMs"}) — do not rename.
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li>Verify application-level timeouts trigger at the expected threshold
 *   <li>Simulate cross-region latency (EU↔US ~100 ms)
 *   <li>Test that connection pools tolerate latency without exhaustion
 *   <li>Verify Lettuce/Jedis/Spring Data Redis timeout configuration
 * </ul>
 *
 * <h2>Real-World Scenarios</h2>
 *
 * <table>
 *   <caption>Latency values by network type</caption>
 *   <tr><th>Scenario</th><th>latencyMs</th><th>jitterMs</th></tr>
 *   <tr><td>Same datacenter</td><td>1–5</td><td>1</td></tr>
 *   <tr><td>Cross-region (EU↔US)</td><td>80–120</td><td>20</td></tr>
 *   <tr><td>Mobile 4G</td><td>30–50</td><td>20</td></tr>
 *   <tr><td>Mobile 3G</td><td>100–200</td><td>50</td></tr>
 *   <tr><td>Satellite</td><td>500–600</td><td>100</td></tr>
 * </table>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * LatencyToxic geoLatency = LatencyToxic.builder()
 *     .name("eu-us-latency")
 *     .latencyMs(80)
 *     .jitterMs(20)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeoutToxic for connection drops after a deadline
 * @see BandwidthToxic for throughput limiting
 */
public final class LatencyToxic extends AbstractToxic {

  private final int latencyMs;
  private final int jitterMs;

  private LatencyToxic(final Builder builder) {
    super(builder);
    this.latencyMs = builder.latencyMs;
    this.jitterMs = builder.jitterMs;
    validateNonNegative(latencyMs, "latencyMs");
    validateNonNegative(jitterMs, "jitterMs");
  }

  /** @return new builder (defaults: latencyMs=0, jitterMs=0, toxicity=1.0) */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String type() {
    return "latency";
  }

  /** Base latency in milliseconds (≥ 0). */
  public int latencyMs() {
    return latencyMs;
  }

  /** Jitter amplitude in milliseconds (≥ 0). */
  public int jitterMs() {
    return jitterMs;
  }

  /** Returns attributes-only JSON: {@code {"latency":N,"jitter":N}}. */
  @Override
  public String toJson() {
    return String.format("{\"latency\":%d,\"jitter\":%d}", latencyMs, jitterMs);
  }

  /** Builder for {@link LatencyToxic}. */
  public static final class Builder extends AbstractBuilder<Builder> {

    private int latencyMs = 0;
    private int jitterMs = 0;

    private Builder() {}

    /**
     * Base delay added to every data chunk (default: 0, must be ≥ 0).
     *
     * @param latencyMs base delay in milliseconds
     * @return this builder
     */
    public Builder latencyMs(final int latencyMs) {
      this.latencyMs = latencyMs;
      return this;
    }

    /**
     * Random jitter around the base latency (default: 0, must be ≥ 0).
     *
     * @param jitterMs jitter amplitude in milliseconds
     * @return this builder
     */
    public Builder jitterMs(final int jitterMs) {
      this.jitterMs = jitterMs;
      return this;
    }

    /**
     * Build the {@link LatencyToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if any value is out of range
     */
    public LatencyToxic build() {
      return new LatencyToxic(this);
    }
  }
}
