/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Adds fixed latency and optional jitter to all data flowing through the proxy.
 *
 * <p>Uses Toxiproxy's {@code latency} toxic, which delays every data chunk by
 * {@code latencyMs ± jitterMs} milliseconds before forwarding it. Both upstream and downstream
 * traffic are affected.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>latencyMs</strong> — base delay added to every chunk of data. {@code 0} means
 *       no base delay (jitter only).</li>
 *   <li><strong>jitterMs</strong> — random variation uniformly distributed in
 *       {@code [-jitter, +jitter]}. Actual delay per chunk:
 *       {@code latencyMs + random(-jitterMs, +jitterMs)}. {@code 0} means deterministic.</li>
 *   <li><strong>toxicity</strong> — fraction of connections affected. {@code 1.0} applies latency
 *       to every connection; {@code 0.5} applies it to approximately half.</li>
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
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * // Deterministic 100ms latency on all connections (baseline degradation test)
 * LatencyToxic latency = LatencyToxic.builder()
 *     .name("datacenter-lag")
 *     .latencyMs(100)
 *     .build();
 *
 * // Cross-region simulation: 80ms base ± 20ms jitter on all connections
 * LatencyToxic geoLatency = LatencyToxic.builder()
 *     .name("eu-us-latency")
 *     .latencyMs(80)
 *     .jitterMs(20)
 *     .build();
 *
 * // Intermittent slowdown: 500ms latency on 20% of connections
 * LatencyToxic flaky = LatencyToxic.builder()
 *     .name("flaky-upstream")
 *     .latencyMs(500)
 *     .toxicity(0.2)
 *     .build();
 * }</pre>
 *
 * <h2>Convenience Method</h2>
 *
 * <p>For simple cases, prefer the {@link com.macstab.chaos.proxy.ProxyChaosProvider} convenience
 * methods which build this toxic internally:
 *
 * <pre>{@code
 * chaos.addLatency(container, "redis", Duration.ofMillis(100));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeoutToxic for connection drops after a deadline
 * @see BandwidthToxic for throughput limiting
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

  /**
   * Create a new builder.
   *
   * <p>Defaults: {@code latencyMs=0}, {@code jitterMs=0}, {@code toxicity=1.0}.
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
    return "latency";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  /**
   * Base latency in milliseconds.
   *
   * @return base delay (≥ 0)
   */
  public int latencyMs() {
    return latencyMs;
  }

  /**
   * Jitter in milliseconds (random variation around {@link #latencyMs()}).
   *
   * @return jitter amplitude (≥ 0)
   */
  public int jitterMs() {
    return jitterMs;
  }

  @Override
  public String toJson() {
    return String.format("{\"latency\":%d,\"jitter\":%d}", latencyMs, jitterMs);
  }

  // ==================== Validation ====================

  private static void validateLatency(final int latency) {
    if (latency < 0) {
      throw new IllegalArgumentException("latencyMs must be >= 0, got: " + latency);
    }
  }

  private static void validateJitter(final int jitter) {
    if (jitter < 0) {
      throw new IllegalArgumentException("jitterMs must be >= 0, got: " + jitter);
    }
  }

  private static void validateToxicity(final double toxic) {
    if (toxic < 0.0 || toxic > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxic));
    }
  }

  // ==================== Builder ====================

  /**
   * Builder for {@link LatencyToxic}.
   *
   * <p>All fields have defaults: {@code latencyMs=0}, {@code jitterMs=0},
   * {@code toxicity=1.0}. Only {@link #name(String)} is required.
   */
  public static final class Builder {

    private String name;
    private int latencyMs = 0;
    private int jitterMs = 0;
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
     * Set the base latency added to every data chunk.
     *
     * <p>Default: {@code 0} (no base delay). Must be ≥ 0.
     *
     * @param latencyMs base delay in milliseconds
     * @return this builder
     * @throws IllegalArgumentException if negative
     */
    public Builder latencyMs(final int latencyMs) {
      this.latencyMs = latencyMs;
      return this;
    }

    /**
     * Set the random jitter applied around the base latency.
     *
     * <p>Actual per-chunk delay: {@code latencyMs + random(-jitterMs, +jitterMs)}.
     * Default: {@code 0} (deterministic). Must be ≥ 0.
     *
     * @param jitterMs jitter amplitude in milliseconds
     * @return this builder
     * @throws IllegalArgumentException if negative
     */
    public Builder jitterMs(final int jitterMs) {
      this.jitterMs = jitterMs;
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
