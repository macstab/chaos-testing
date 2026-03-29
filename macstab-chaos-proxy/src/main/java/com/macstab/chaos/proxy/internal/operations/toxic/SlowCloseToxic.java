/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Delays the TCP close sequence by holding the connection open for a configurable duration
 * after all data has been transferred.
 *
 * <p>Uses Toxiproxy's {@code slow_close} toxic. When the upstream signals EOF (end of response),
 * the toxic delays forwarding the close signal to the client by {@code delayMs} milliseconds.
 * Data transfer is unaffected — only the close phase is delayed.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>delayMs=0</strong> — no delay; TCP close is forwarded immediately. Effectively
 *       a no-op and rarely useful in practice.</li>
 *   <li><strong>delayMs &gt; 0</strong> — the connection is held open for {@code delayMs}
 *       milliseconds after data transfer completes. The client cannot reuse the socket until
 *       the close arrives. Connection pools waiting for the socket to free will block.</li>
 *   <li><strong>toxicity</strong> — fraction of connections that experience the delayed close.
 *       Use values below {@code 1.0} to simulate a partially degraded connection pool.</li>
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Connection pool exhaustion:</strong> If pool size is small and close delay is
 *       longer than the pool timeout, new requests block waiting for a free connection. Tests
 *       whether the application fails fast (pool exhaustion error) or hangs.</li>
 *   <li><strong>Keepalive pressure:</strong> Simulates upstreams that are slow to acknowledge
 *       close — common with overloaded databases or legacy services.</li>
 *   <li><strong>TIME_WAIT accumulation:</strong> Rapid request cycles with slow close can cause
 *       TIME_WAIT socket accumulation. Tests whether the application handles ephemeral port
 *       exhaustion gracefully.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * // Hold connection open for 5 seconds after data — exhaust a pool of 10 connections
 * // in under 10 seconds of sustained load
 * SlowCloseToxic poolExhaustion = SlowCloseToxic.builder()
 *     .name("slow-close")
 *     .delayMs(5000)
 *     .toxicity(1.0)
 *     .build();
 *
 * // Intermittent slow close on 20% of connections — realistic overloaded upstream
 * SlowCloseToxic partial = SlowCloseToxic.builder()
 *     .name("partial-slow-close")
 *     .delayMs(2000)
 *     .toxicity(0.2)
 *     .build();
 * }</pre>
 *
 * <h2>Convenience Method</h2>
 *
 * <p>For simple cases, prefer {@link com.macstab.chaos.proxy.ProxyChaosProvider#slowClose}:
 *
 * <pre>{@code
 * chaos.slowClose(container, "db", Duration.ofSeconds(5));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeoutToxic for dropping the connection entirely rather than just delaying the close
 * @see LimitDataToxic for closing the connection after a byte threshold
 */
public final class SlowCloseToxic implements ToxicConfig {

  private final String name;
  private final int delayMs;
  private final double toxicity;

  private SlowCloseToxic(final Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.delayMs = builder.delayMs;
    this.toxicity = builder.toxicity;

    validate();
  }

  /**
   * Create a new builder.
   *
   * <p>Defaults: {@code delayMs=0}, {@code toxicity=1.0}.
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
    return "slow_close";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  /**
   * Duration the TCP close signal is held back after data transfer completes, in milliseconds.
   *
   * <p>{@code 0} means no delay. Positive values hold the connection open for the specified
   * duration, blocking socket reuse in the client's connection pool.
   *
   * @return close delay in milliseconds (≥ 0)
   */
  public int delayMs() {
    return delayMs;
  }

  @Override
  public String toJson() {
    return String.format("{\"delay\":%d}", delayMs);
  }

  private void validate() {
    if (delayMs < 0) {
      throw new IllegalArgumentException("delayMs must be >= 0, got: " + delayMs);
    }
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  // ==================== Builder ====================

  /**
   * Builder for {@link SlowCloseToxic}.
   *
   * <p>Defaults: {@code delayMs=0}, {@code toxicity=1.0}. Only {@link #name(String)} is required.
   */
  public static final class Builder {

    private String name;
    private int delayMs = 0;
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
     * Set the duration to hold the connection open after data transfer, before forwarding the
     * TCP close signal.
     *
     * <p>Default: {@code 0} (no delay). Must be ≥ 0. Higher values increase the pressure on
     * the client connection pool.
     *
     * @param delayMs close delay in milliseconds
     * @return this builder
     * @throws IllegalArgumentException if negative
     */
    public Builder delayMs(final int delayMs) {
      this.delayMs = delayMs;
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
     * Build the {@link SlowCloseToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     * @throws IllegalArgumentException if any value is out of range
     */
    public SlowCloseToxic build() {
      return new SlowCloseToxic(this);
    }
  }
}
