/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Halts all data flowing through the proxy and forcibly closes the connection after a configurable
 * hang duration.
 *
 * <p>Uses Toxiproxy's {@code timeout} toxic. The toxic intercepts data at the proxy level: when a
 * connection is affected, Toxiproxy stops forwarding data immediately and waits for {@code
 * timeoutMs} milliseconds before issuing a TCP RST to the client. The client experiences a
 * connection that appears to have stalled, then receives a connection error after exactly {@code
 * timeoutMs} milliseconds.
 *
 * <h2>timeoutMs=0 Semantics</h2>
 *
 * <p>With {@code timeoutMs=0}, Toxiproxy issues the TCP RST on the first data arrival — the
 * connection is dropped as soon as it carries data. This simulates an upstream that accepts the TCP
 * handshake (SYN/SYN-ACK/ACK) but immediately resets on the first payload. This is subtly different
 * from "connection refused" (which fails the SYN-ACK) — clients that distinguish between
 * TCP-refused and TCP-reset may behave differently.
 *
 * <h2>Difference from DownToxic</h2>
 *
 * <p>{@link DownToxic} drops data without a configurable delay. {@code TimeoutToxic} introduces a
 * measurable hang before the drop, which is essential for testing client-side timeout configuration
 * — you want to verify that the client's configured timeout is shorter than the upstream's failure
 * duration.
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li>Verify circuit breaker trips at the expected threshold
 *   <li>Validate that HikariCP/Lettuce connection timeouts are shorter than service SLA
 *   <li>Test retry logic under intermittent connection failures ({@code toxicity} &lt; 1.0)
 *   <li>Simulate an upstream that is accepting connections but not processing them (overloaded)
 * </ul>
 *
 * <h2>Real-World Scenarios</h2>
 *
 * <table>
 *   <caption>Timeout configurations by scenario</caption>
 *   <tr><th>Scenario</th><th>timeoutMs</th><th>toxicity</th></tr>
 *   <tr><td>Instant connection drop</td><td>0</td><td>1.0</td></tr>
 *   <tr><td>Overloaded upstream (30% timeout after 5s)</td><td>5000</td><td>0.3</td></tr>
 *   <tr><td>Circuit breaker trip test</td><td>1000</td><td>0.5</td></tr>
 * </table>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * TimeoutToxic partial = TimeoutToxic.builder()
 *     .name("overloaded-upstream")
 *     .timeoutMs(5000)
 *     .toxicity(0.3)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LatencyToxic for adding delay without dropping the connection
 */
public final class TimeoutToxic extends AbstractToxic {

  private final int timeoutMs;

  private TimeoutToxic(final Builder builder) {
    super(builder);
    this.timeoutMs = builder.timeoutMs;
    validateNonNegative(timeoutMs, "timeoutMs");
  }

  /**
   * @return new builder (defaults: timeoutMs=0, toxicity=1.0)
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String type() {
    return "timeout";
  }

  /** Hang duration before connection close in milliseconds (≥ 0, 0 = instant drop). */
  public int timeoutMs() {
    return timeoutMs;
  }

  /** Returns attributes-only JSON: {@code {"timeout":N}}. */
  @Override
  public String toJson() {
    return String.format("{\"timeout\":%d}", timeoutMs);
  }

  /** Builder for {@link TimeoutToxic}. */
  public static final class Builder extends AbstractBuilder<Builder> {

    private int timeoutMs = 0;

    private Builder() {}

    /**
     * Hang duration before connection drop (default: 0 = instant, must be ≥ 0).
     *
     * @param timeoutMs hang duration in milliseconds
     * @return this builder
     */
    public Builder timeoutMs(final int timeoutMs) {
      this.timeoutMs = timeoutMs;
      return this;
    }

    /**
     * Build the {@link TimeoutToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if any value is out of range
     */
    public TimeoutToxic build() {
      return new TimeoutToxic(this);
    }
  }
}
