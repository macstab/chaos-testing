/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Drops all data flowing through the proxy immediately, without delay, simulating a completely
 * unreachable upstream or probabilistic packet loss at the TCP application layer.
 *
 * <p>Uses Toxiproxy's {@code down} toxic, which has no attributes. When active, Toxiproxy does not
 * forward any data through affected connections. The client's socket appears connected at the TCP
 * level (the handshake completes) but no application data flows. Depending on the client's socket
 * read timeout, the client will eventually receive a timeout error or a connection reset.
 *
 * <h2>Difference from Other Drop Toxics</h2>
 *
 * <p>Comparison of connection-drop behavior:
 *
 * <ul>
 *   <li>{@code DownToxic}: TCP handshake succeeds, but data is silently dropped. Client hangs until
 *       its own read timeout fires. No explicit RST sent.
 *   <li>{@link TimeoutToxic} with {@code timeoutMs=0}: data dropped with explicit TCP RST after the
 *       first data arrival.
 *   <li>{@link LimitDataToxic} with {@code bytes=0}: TCP RST issued after handshake.
 * </ul>
 *
 * <p>The silent drop behavior of {@code DownToxic} is the most realistic simulation of a "black
 * hole" network — packets arrive but are discarded without acknowledgment, which is what happens
 * with routing loops, firewall DROP rules (as opposed to REJECT), and some network partition
 * scenarios.
 *
 * <h2>Probabilistic Use: Packet Loss Simulation</h2>
 *
 * <p>With {@code toxicity < 1.0}, {@code DownToxic} simulates packet loss: each connection has an
 * independent {@code toxicity}-probability of being silently dropped. This is useful for testing
 * retry logic under flaky network conditions. Unlike the kernel-level packet loss simulation in
 * {@code com.macstab.chaos.network.TcNetworkChaos#injectPacketLoss} (sibling module), this
 * operates at the connection level (not per-packet) — an affected connection drops all its data,
 * not a fraction of packets.
 *
 * <h2>Attributes JSON</h2>
 *
 * <p>{@link #toJson()} returns {@code {}} (empty object). The Toxiproxy {@code down} toxic has no
 * configurable attributes in Toxiproxy 2.x; the type itself is the configuration.
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li>Simulate complete upstream failure (circuit breaker testing)
 *   <li>Test application behavior when dependent services are unreachable
 *   <li>Verify timeout configuration is set (no timeout = connection hangs indefinitely)
 *   <li>Simulate 30% packet loss for flaky-network retry logic testing
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

  /**
   * @return new builder (defaults: toxicity=1.0)
   */
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
