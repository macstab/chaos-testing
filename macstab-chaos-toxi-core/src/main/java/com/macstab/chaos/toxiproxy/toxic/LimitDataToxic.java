/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Closes the TCP connection after a configurable number of cumulative bytes have been transmitted
 * in both directions through the proxy, simulating mid-stream connection failures.
 *
 * <p>Uses Toxiproxy's {@code limit_data} toxic. Toxiproxy maintains a byte counter per affected
 * connection tracking the total of upstream + downstream traffic. Once the counter exceeds {@code
 * bytes}, the connection is immediately closed with a TCP RST. The client receives a partial
 * response — it may have received anywhere from 0 to {@code bytes} bytes of the complete response
 * payload.
 *
 * <h2>Bidirectional Byte Counting</h2>
 *
 * <p>The byte counter includes both directions: client-to-service (request bytes) and
 * service-to-client (response bytes). For a Redis {@code SET} command, the request is ~10–30 bytes
 * and the response is ~5 bytes — total ~35 bytes. Setting {@code bytes=20} would typically
 * interrupt the connection mid-request before the command reaches Redis. Setting {@code bytes=200}
 * with a HGETALL that returns a 150-byte hash would allow the full response to arrive, then close
 * the connection before the next command.
 *
 * <h2>bytes=0 Semantics: Immediate Reset</h2>
 *
 * <p>When {@code bytes=0}, Toxiproxy issues a TCP RST immediately upon connection establishment,
 * before any application data is exchanged. This is subtly different from {@link TimeoutToxic} with
 * {@code timeoutMs=0}: both drop the connection immediately, but {@code LimitDataToxic} with {@code
 * bytes=0} allows the TCP handshake to complete (the client sees a connected socket) before
 * closing, while a refused connection (no Toxiproxy) would fail the handshake entirely.
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li>Verify clients handle {@code EOFException} and {@code SocketException} mid-stream
 *   <li>Test reconnection logic under rapid connection churn ({@code bytes} set low)
 *   <li>Expose missing retry logic for partial read failures in streaming protocols
 *   <li>Simulate network interruptions during large blob downloads or streaming responses
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

  /**
   * @return new builder (defaults: bytes=0, toxicity=1.0)
   */
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
