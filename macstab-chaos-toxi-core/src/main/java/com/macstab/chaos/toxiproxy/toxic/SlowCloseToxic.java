/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Delays forwarding of the TCP close (FIN) signal from the upstream to the client, holding the
 * connection socket open for a configurable duration after all data has been transferred.
 *
 * <p>Uses Toxiproxy's {@code slow_close} toxic. Toxiproxy intercepts the upstream's EOF (FIN
 * packet) and delays forwarding it to the client by {@code delayMs} milliseconds. During this
 * delay, all data has been delivered but the socket is not yet in CLOSE_WAIT state. The client
 * cannot release the socket back to its connection pool until the FIN arrives.
 *
 * <h2>Connection Pool Exhaustion Mechanism</h2>
 *
 * <p>This toxic is specifically designed to trigger connection pool exhaustion. If a pool has a
 * maximum size of N connections and the service processes N requests simultaneously, each
 * connection will be held open for {@code delayMs} ms after the response is complete. If the pool's
 * maximum wait time is shorter than {@code delayMs}, requests arriving during this window will fail
 * with a pool timeout exception. This tests whether the application fails fast (pool exhaustion
 * error) or hangs indefinitely.
 *
 * <h2>Difference from TimeoutToxic</h2>
 *
 * <p>{@link TimeoutToxic} halts data transfer and drops the connection. {@code SlowCloseToxic}
 * completes data transfer normally — the client receives the full response — but the socket cleanup
 * is delayed. This difference is observable: clients that measure "time to first byte" or "time to
 * last byte" are not affected by {@code SlowCloseToxic}, but clients that measure "time to
 * connection available" (i.e., connection pool check-out time) are affected.
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li>Verify connection pool exhaustion behavior (HikariCP, Lettuce, R2DBC)
 *   <li>Test application behavior when socket reuse is delayed (keepalive under load)
 *   <li>Expose missing connection pool timeout configuration
 *   <li>Verify that connection pool size is appropriate for peak load
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 *
 * <ul>
 *   <li>Connection pool exhaustion testing
 *   <li>Keepalive pressure simulation
 *   <li>TIME_WAIT accumulation testing
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * SlowCloseToxic poolExhaustion = SlowCloseToxic.builder()
 *     .name("slow-close")
 *     .delayMs(5000)
 *     .toxicity(1.0)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeoutToxic for dropping the connection rather than just delaying close
 */
public final class SlowCloseToxic extends AbstractToxic {

  private final int delayMs;

  private SlowCloseToxic(final Builder builder) {
    super(builder);
    this.delayMs = builder.delayMs;
    validateNonNegative(delayMs, "delayMs");
  }

  /**
   * @return new builder (defaults: delayMs=0, toxicity=1.0)
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String type() {
    return "slow_close";
  }

  /** Close delay in milliseconds (≥ 0). */
  public int delayMs() {
    return delayMs;
  }

  /** Returns attributes-only JSON: {@code {"delay":N}}. */
  @Override
  public String toJson() {
    return String.format("{\"delay\":%d}", delayMs);
  }

  /** Builder for {@link SlowCloseToxic}. */
  public static final class Builder extends AbstractBuilder<Builder> {

    private int delayMs = 0;

    private Builder() {}

    /**
     * Duration to hold the connection open after data transfer (default: 0, must be ≥ 0).
     *
     * @param delayMs close delay in milliseconds
     * @return this builder
     */
    public Builder delayMs(final int delayMs) {
      this.delayMs = delayMs;
      return this;
    }

    /**
     * Build the {@link SlowCloseToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if delayMs is negative
     */
    public SlowCloseToxic build() {
      return new SlowCloseToxic(this);
    }
  }
}
