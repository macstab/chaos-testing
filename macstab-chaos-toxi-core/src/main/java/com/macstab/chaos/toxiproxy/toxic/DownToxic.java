/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Drops all traffic through the proxy, simulating a completely down upstream.
 *
 * <p>Uses Toxiproxy's {@code down} toxic. All new connections are refused immediately.
 * Unlike {@link TimeoutToxic}, there is no delay — the connection is refused on first data.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><strong>toxicity=1.0</strong> — all connections dropped (complete outage).
 *   <li><strong>toxicity &lt; 1.0</strong> — probabilistic packet loss (e.g., 0.3 = 30%).
 * </ul>
 *
 * <h2>Real-World Scenarios</h2>
 *
 * <table>
 *   <caption>Down toxic configurations by scenario</caption>
 *   <tr><th>Scenario</th><th>toxicity</th></tr>
 *   <tr><td>Complete upstream outage</td><td>1.0</td></tr>
 *   <tr><td>30% packet loss</td><td>0.3</td></tr>
 *   <tr><td>Occasional drops (5%)</td><td>0.05</td></tr>
 * </table>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * DownToxic outage = DownToxic.builder()
 *     .name("upstream-down")
 *     .toxicity(1.0)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class DownToxic extends AbstractToxic {

  private DownToxic(final Builder builder) {
    super(builder);
  }

  /** @return new builder (defaults: toxicity=1.0) */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String type() {
    return "down";
  }

  /** Returns attributes-only JSON: {@code {}} (down toxic has no attributes). */
  @Override
  public String toJson() {
    return "{}";
  }

  /** Builder for {@link DownToxic}. */
  public static final class Builder extends AbstractBuilder<Builder> {

    private Builder() {}

    /**
     * Build the {@link DownToxic} instance.
     *
     * @return immutable toxic configuration
     * @throws NullPointerException if {@code name} was not set
     */
    public DownToxic build() {
      return new DownToxic(this);
    }
  }
}
