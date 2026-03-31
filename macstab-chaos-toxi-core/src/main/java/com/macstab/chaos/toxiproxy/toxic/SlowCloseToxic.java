/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Delays the TCP close sequence after all data has been transferred.
 *
 * <p>Uses Toxiproxy's {@code slow_close} toxic. Data transfer is unaffected — only the close
 * phase is delayed. Connection pools waiting for the socket to free will block.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>delayMs=0</strong> — no delay (no-op).
 *   <li><strong>delayMs &gt; 0</strong> — connection held open for {@code delayMs} ms after EOF.
 *   <li><strong>toxicity</strong> — fraction of connections that experience the delayed close.
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

  /** @return new builder (defaults: delayMs=0, toxicity=1.0) */
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
