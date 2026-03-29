/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Closes the connection after a fixed number of bytes have been transmitted through the proxy.
 *
 * <p>Uses Toxiproxy's {@code limit_data} toxic. The byte counter tracks data flowing in
 * <em>both</em> directions combined (upstream + downstream). Once the cumulative byte count
 * reaches the threshold, the connection is closed immediately — the client receives a
 * connection reset mid-stream.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>bytes=0</strong> — the connection is closed immediately upon establishment,
 *       before any data is exchanged. Equivalent to an instant TCP reset after connect.</li>
 *   <li><strong>bytes &gt; 0</strong> — the connection is closed after exactly {@code bytes}
 *       of combined bidirectional data. The client receives a partial response, which exercises
 *       error handling for incomplete reads.</li>
 *   <li><strong>toxicity</strong> — fraction of connections that hit the byte limit.
 *       {@code 1.0} limits every connection; lower values simulate intermittent partial
 *       failures.</li>
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Partial read resilience:</strong> Verify that the application correctly detects
 *       and handles truncated responses — e.g., a JSON body that is cut off mid-parse, or a
 *       binary protocol frame that arrives incomplete. The application must not silently accept
 *       partial data as complete.</li>
 *   <li><strong>Streaming fault injection:</strong> For large response bodies or streaming
 *       protocols (SSE, gRPC, NATS), set {@code bytes} to a value mid-stream to simulate
 *       a network interruption partway through the transfer.</li>
 *   <li><strong>Connection recycling pressure:</strong> With {@code bytes} set to a small value
 *       (e.g., 64), connections are recycled after almost every protocol exchange, exercising
 *       reconnection logic and connection pool stability under churn.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * // Close after 1 KB — truncate most Redis responses mid-body
 * LimitDataToxic truncate = LimitDataToxic.builder()
 *     .name("truncate-response")
 *     .bytes(1024)
 *     .toxicity(1.0)
 *     .build();
 *
 * // Immediate close on connect — verify reconnection logic
 * LimitDataToxic instantClose = LimitDataToxic.builder()
 *     .name("instant-reset")
 *     .bytes(0)
 *     .toxicity(1.0)
 *     .build();
 *
 * // Close after 100KB on 25% of connections — simulate intermittent partial downloads
 * LimitDataToxic partialDownload = LimitDataToxic.builder()
 *     .name("partial-download")
 *     .bytes(102_400)
 *     .toxicity(0.25)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeoutToxic for dropping the connection after a time threshold rather than a byte count
 * @see BandwidthToxic for throttling throughput without closing the connection
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

  /**
   * Create a new builder.
   *
   * <p>Defaults: {@code bytes=0} (instant close on connect), {@code toxicity=1.0}.
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
    return "limit_data";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  /**
   * Cumulative byte threshold (upstream + downstream) at which the connection is closed.
   *
   * <p>{@code 0} closes the connection immediately on establishment. Positive values allow
   * exactly that many bytes through before the connection reset.
   *
   * @return byte limit (≥ 0)
   */
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

  // ==================== Builder ====================

  /**
   * Builder for {@link LimitDataToxic}.
   *
   * <p>Defaults: {@code bytes=0} (instant close), {@code toxicity=1.0}. Only
   * {@link #name(String)} is required.
   */
  public static final class Builder {

    private String name;
    private long bytes = 0;
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
     * Set the cumulative byte threshold after which the connection is closed.
     *
     * <p>Counts both upstream and downstream data combined. {@code 0} (default) closes
     * the connection immediately on establishment — useful for testing reconnection logic.
     * Must be ≥ 0.
     *
     * @param bytes byte limit (0 = instant close on connect)
     * @return this builder
     * @throws IllegalArgumentException if negative
     */
    public Builder bytes(final long bytes) {
      this.bytes = bytes;
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
     * Build the {@link LimitDataToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if any value is out of range
     */
    public LimitDataToxic build() {
      return new LimitDataToxic(this);
    }
  }
}
