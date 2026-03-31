/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Closes the connection after a fixed number of bytes have been transmitted (upstream + downstream).
 *
 * <p>Uses Toxiproxy's {@code limit_data} toxic. The client receives a connection reset mid-stream
 * once the cumulative byte count reaches the threshold.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>bytes=0</strong> — instant TCP reset after connect, before any data.
 *   <li><strong>bytes &gt; 0</strong> — partial response; exercises incomplete-read handling.
 *   <li><strong>toxicity</strong> — fraction of connections that hit the byte limit.
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 *
 * <ul>
 *   <li>Partial read resilience (truncated JSON, binary protocol frames)
 *   <li>Streaming fault injection (SSE, gRPC, NATS)
 *   <li>Connection recycling pressure testing
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * LimitDataToxic truncate = LimitDataToxic.builder()
 *     .name("truncate-response")
 *     .bytes(1024)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeoutToxic for time-based connection drops
 * @see BandwidthToxic for throughput throttling without closing
 */
public final class LimitDataToxic extends AbstractToxic {

  private final long bytes;

  private LimitDataToxic(final Builder builder) {
    super(builder);
    this.bytes = builder.bytes;
    validateNonNegative(bytes, "bytes");
  }

  /** @return new builder (defaults: bytes=0, toxicity=1.0) */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String type() {
    return "limit_data";
  }

  /** Byte threshold at which the connection is closed (≥ 0, 0 = instant reset). */
  public long bytes() {
    return bytes;
  }

  /** Returns attributes-only JSON: {@code {"bytes":N}}. */
  @Override
  public String toJson() {
    return String.format("{\"bytes\":%d}", bytes);
  }

  /** Builder for {@link LimitDataToxic}. */
  public static final class Builder extends AbstractBuilder<Builder> {

    private long bytes = 0;

    private Builder() {}

    /**
     * Byte threshold before connection close (default: 0 = instant reset, must be ≥ 0).
     *
     * @param bytes cumulative byte limit (upstream + downstream combined)
     * @return this builder
     */
    public Builder bytes(final long bytes) {
      this.bytes = bytes;
      return this;
    }

    /**
     * Build the {@link LimitDataToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if bytes is negative
     */
    public LimitDataToxic build() {
      return new LimitDataToxic(this);
    }
  }
}
