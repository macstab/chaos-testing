/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Type-safe configuration for a Toxiproxy fault injection (toxic).
 *
 * <p>A toxic intercepts TCP data flowing through a Toxiproxy proxy and applies a configurable fault
 * — delay, bandwidth limit, connection drop, etc. This sealed interface ensures all toxic
 * configurations are exhaustive and carry the attributes needed for the Toxiproxy REST API.
 *
 * <h2>Toxic vs Proxy</h2>
 *
 * <p>A <em>proxy</em> is the transparent TCP intercept (created via {@link
 * com.macstab.chaos.proxy.ProxyChaosProvider#createProxy}). A <em>toxic</em> is a fault applied to
 * traffic flowing through that proxy. Multiple toxics can be active on the same proxy
 * simultaneously; each must have a unique {@link #name()}.
 *
 * <h2>Toxicity Probability</h2>
 *
 * <p>The {@link #toxicity()} value controls what fraction of connections are affected:
 *
 * <ul>
 *   <li>{@code 1.0} — every connection is affected (deterministic)
 *   <li>{@code 0.5} — 50% of connections are affected (probabilistic)
 *   <li>{@code 0.0} — no connections are affected (toxic is installed but dormant)
 * </ul>
 *
 * <p>Probabilistic toxics simulate flaky infrastructure where faults occur intermittently, which is
 * more realistic than always-on fault injection.
 *
 * <h2>Available Implementations</h2>
 *
 * <ul>
 *   <li>{@link LatencyToxic} — add fixed or jittered latency (simulate slow networks)
 *   <li>{@link TimeoutToxic} — drop connection after timeout (simulate hung servers)
 *   <li>{@link BandwidthToxic} — cap throughput in KB/s (simulate slow links)
 *   <li>{@link SlowCloseToxic} — delay TCP close (simulate connection pool exhaustion)
 *   <li>{@link LimitDataToxic} — close connection after N bytes (simulate partial reads)
 * </ul>
 *
 * <h2>Direct Usage</h2>
 *
 * <p>Use typed implementations when you need full control over toxic parameters:
 *
 * <pre>{@code
 * // Advanced: typed toxic with custom parameters
 * LatencyToxic toxic = LatencyToxic.builder()
 *     .name("geo-latency")
 *     .latencyMs(80)
 *     .jitterMs(20)
 *     .toxicity(1.0)
 *     .build();
 *
 * // The orchestrator / ToxicOperations accept ToxicConfig directly
 * toxicOps.addToxic(ctx, "redis", toxic);
 * }</pre>
 *
 * <p>For convenience, use {@link com.macstab.chaos.proxy.ProxyChaosProvider} methods such as {@code
 * addLatency} and {@code limitBandwidth} which build the typed config internally.
 *
 * <h2>JSON Serialization</h2>
 *
 * <p>{@link #toJson()} produces the {@code attributes} object for the Toxiproxy REST API: {@code
 * POST /proxies/{name}/toxics}. Each implementation maps its fields to the Toxiproxy attribute
 * schema documented at <a href="https://github.com/Shopify/toxiproxy#toxics">Toxiproxy Toxics</a>.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.proxy.ProxyChaosProvider
 */
public sealed interface ToxicConfig
    permits LatencyToxic, TimeoutToxic, BandwidthToxic, SlowCloseToxic, LimitDataToxic, DownToxic {

  /**
   * Unique name for this toxic within its proxy.
   *
   * <p>The name is the Toxiproxy REST API key for this toxic entry. Two toxics on the same proxy
   * must have different names. Attempting to add a toxic with a duplicate name is idempotent — the
   * existing toxic is kept unchanged.
   *
   * @return unique toxic name (e.g., {@code "geo-latency"}, {@code "upstream-timeout"})
   */
  String name();

  /**
   * Toxiproxy type identifier used in the REST API payload.
   *
   * <p>Maps to the {@code "type"} field in {@code POST /proxies/{name}/toxics}. Each implementation
   * returns a fixed value matching the Toxiproxy server's registered type names (e.g., {@code
   * "latency"}, {@code "timeout"}, {@code "bandwidth"}).
   *
   * @return Toxiproxy type string
   */
  String type();

  /**
   * Fraction of connections this toxic is applied to (0.0–1.0).
   *
   * <p>Controls probabilistic fault injection:
   *
   * <ul>
   *   <li>{@code 1.0} — all connections are affected (default, deterministic testing)
   *   <li>{@code 0.5} — approximately half of connections are affected
   *   <li>{@code 0.0} — no connections are affected (toxic installed but inactive)
   * </ul>
   *
   * <p>Use values below {@code 1.0} to simulate intermittent failures that occur in real
   * infrastructure — flaky DNS, partial network partitions, overloaded upstreams.
   *
   * @return toxicity probability in [0.0, 1.0]
   */
  double toxicity();

  /**
   * Serialize this toxic's attributes to a JSON object string for the Toxiproxy REST API.
   *
   * <p>The returned string is the value of the {@code "attributes"} field in the toxic creation
   * payload. For example, {@link LatencyToxic} returns {@code {"latency":100,"jitter":0}}.
   *
   * <p>This method is called internally by {@link
   * com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient#addToxic} and is not intended for direct use by
   * test code.
   *
   * @return JSON object string (e.g., {@code {"latency":100,"jitter":0}})
   */
  String toJson();
}
