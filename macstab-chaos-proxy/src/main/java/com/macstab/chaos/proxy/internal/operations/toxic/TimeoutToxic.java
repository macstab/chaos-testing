/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import java.util.Objects;

/**
 * Stops all data flowing through the proxy and closes the connection after a configurable timeout.
 *
 * <p>Uses Toxiproxy's {@code timeout} toxic. When triggered, the toxic halts data transfer
 * immediately and waits for {@code timeoutMs} milliseconds before forcibly closing the connection.
 * The client experiences either a hang (during the wait) followed by a connection reset, or — with
 * {@code timeoutMs=0} — an instant connection drop.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>timeoutMs=0</strong> — connection is closed immediately upon first data. Use this
 *       to simulate a completely unresponsive upstream (connection refused after TCP handshake).
 *       This is the most aggressive timeout scenario.
 *   <li><strong>timeoutMs &gt; 0</strong> — data is held for {@code timeoutMs} ms then the
 *       connection is dropped. The client sees a hang of exactly that duration followed by an
 *       error. Use this to test timeout handling and circuit breaker trip times.
 *   <li><strong>toxicity</strong> — fraction of connections that experience the timeout. {@code
 *       0.3} simulates a service that times out 30% of requests — a realistic overloaded upstream
 *       scenario.
 * </ul>
 *
 * <h2>Real-World Scenarios</h2>
 *
 * <table>
 *   <caption>Timeout configurations by scenario</caption>
 *   <tr><th>Scenario</th><th>timeoutMs</th><th>toxicity</th></tr>
 *   <tr><td>Instant connection drop</td><td>0</td><td>1.0</td></tr>
 *   <tr><td>Overloaded upstream (30% requests timeout after 5s)</td><td>5000</td><td>0.3</td></tr>
 *   <tr><td>Client timeout threshold test (just over limit)</td><td>configured + 100ms</td><td>1.0</td></tr>
 *   <tr><td>Circuit breaker trip test</td><td>1000</td><td>0.5</td></tr>
 * </table>
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * // Instant drop: verify circuit breaker opens
 * TimeoutToxic instantDrop = TimeoutToxic.builder()
 *     .name("instant-drop")
 *     .timeoutMs(0)
 *     .toxicity(1.0)
 *     .build();
 *
 * // 5-second hang on 30% of connections: test client timeout handling
 * TimeoutToxic partialTimeout = TimeoutToxic.builder()
 *     .name("overloaded-upstream")
 *     .timeoutMs(5000)
 *     .toxicity(0.3)
 *     .build();
 *
 * // Just over client timeout (e.g., client timeout is 3s, toxic is 3.1s)
 * TimeoutToxic tripClientTimeout = TimeoutToxic.builder()
 *     .name("trip-timeout")
 *     .timeoutMs(3100)
 *     .toxicity(1.0)
 *     .build();
 * }</pre>
 *
 * <h2>Convenience Method</h2>
 *
 * <p>For simple cases, prefer {@link com.macstab.chaos.proxy.ProxyChaosProvider#addTimeout}:
 *
 * <pre>{@code
 * chaos.addTimeout(container, "redis", Duration.ofSeconds(5), 0.3);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LatencyToxic for adding delay without dropping the connection
 * @see SlowCloseToxic for delaying only the connection close phase
 */
public final class TimeoutToxic implements ToxicConfig {

  private final String name;
  private final int timeoutMs;
  private final double toxicity;

  private TimeoutToxic(final Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.timeoutMs = builder.timeoutMs;
    this.toxicity = builder.toxicity;

    validate();
  }

  /**
   * Create a new builder.
   *
   * <p>Defaults: {@code timeoutMs=0} (instant close), {@code toxicity=1.0}.
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
    return "timeout";
  }

  @Override
  public double toxicity() {
    return toxicity;
  }

  /**
   * Duration the toxic holds data before closing the connection, in milliseconds.
   *
   * <p>{@code 0} means the connection is closed immediately. Positive values create a hang of
   * exactly that duration before the connection is dropped.
   *
   * @return timeout in milliseconds (≥ 0)
   */
  public int timeoutMs() {
    return timeoutMs;
  }

  @Override
  public String toJson() {
    return String.format("{\"timeout\":%d}", timeoutMs);
  }

  private void validate() {
    if (timeoutMs < 0) {
      throw new IllegalArgumentException("timeoutMs must be >= 0, got: " + timeoutMs);
    }
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  // ==================== Builder ====================

  /**
   * Builder for {@link TimeoutToxic}.
   *
   * <p>Defaults: {@code timeoutMs=0} (instant close), {@code toxicity=1.0}. Only {@link
   * #name(String)} is required.
   */
  public static final class Builder {

    private String name;
    private int timeoutMs = 0;
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
     * Set the hang duration before the connection is closed.
     *
     * <p>{@code 0} (default) causes an <strong>instant</strong> connection drop — the most
     * aggressive scenario. Positive values cause the connection to hang for exactly {@code
     * timeoutMs} milliseconds then drop.
     *
     * <p>Must be ≥ 0.
     *
     * @param timeoutMs hang duration in milliseconds (0 = instant drop)
     * @return this builder
     * @throws IllegalArgumentException if negative
     */
    public Builder timeoutMs(final int timeoutMs) {
      this.timeoutMs = timeoutMs;
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
