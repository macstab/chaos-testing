/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

/**
 * Sealed interface representing a complete, type-safe configuration for one Toxiproxy
 * fault-injection toxic (a "toxic").
 *
 * <h2>Conceptual Role: Proxy vs Toxic</h2>
 *
 * <p>In Toxiproxy's model, a <em>proxy</em> is the TCP interception point: it accepts connections
 * on a proxy port and forwards to the real service. A <em>toxic</em> is a fault applied to data
 * flowing <em>through</em> a proxy. Multiple toxics may be active on the same proxy simultaneously,
 * each identified by a unique name. Toxics are additive — a connection may experience both latency
 * and bandwidth limiting if both toxics are active.
 *
 * <h2>Why Sealed (JEP 409)</h2>
 *
 * <p>The sealed interface restricts the set of possible toxic types to a known, compile-time
 * enumeration ({@link AbstractToxic} and its six permitted subclasses). This serves three purposes:
 *
 * <ol>
 *   <li><strong>Exhaustive switch:</strong> Code that switches on {@code ToxicConfig} subtypes
 *       can be verified by the compiler as exhaustive, eliminating defensive {@code default}
 *       branches and the associated dead-code risk.
 *   <li><strong>Serialization safety:</strong> The API client ({@link
 *       com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient}) constructs Toxiproxy REST payloads
 *       from {@link #type()} and {@link #toJson()}. External implementations could return unknown
 *       type strings that Toxiproxy would reject at the HTTP layer. Sealing prevents this.
 *   <li><strong>Framework integrity:</strong> The toxi-core module's internal contract (type
 *       strings, JSON attribute schemas) is validated end-to-end only for the six known
 *       implementations. External implementations would be unsupported and unvalidated.
 * </ol>
 *
 * <h2>Why AbstractToxic, Not Direct permits</h2>
 *
 * <p>The sealed chain is two-level: {@code ToxicConfig permits AbstractToxic}, and
 * {@code AbstractToxic permits LatencyToxic, TimeoutToxic, ...}. A single-level chain
 * ({@code ToxicConfig permits LatencyToxic, ...}) would force each concrete class to
 * re-implement the common {@code name}, {@code toxicity}, validation, and builder scaffold.
 * The two-level chain deduplicates this structure via {@link AbstractToxic} (Template Method
 * pattern, GoF) while preserving the sealed guarantee end-to-end.
 *
 * <h2>JSON Serialization Contract</h2>
 *
 * <p>{@link #toJson()} returns the <strong>attributes-only</strong> JSON object for the toxic
 * (e.g., {@code {"latency":100,"jitter":0}}). The outer envelope — containing {@code "name"},
 * {@code "type"}, and {@code "toxicity"} — is assembled by
 * {@link com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl} in its {@code buildToxicJson()}
 * method. This separation is deliberate: the outer envelope structure is identical for all toxics;
 * only the attributes object is type-specific. Each class need only know its own domain.
 *
 * <h2>Toxicity Probability Model</h2>
 *
 * <p>Toxiproxy implements toxicity as a <em>Bernoulli trial per connection</em>. Each new
 * connection independently has probability {@code toxicity} of experiencing the fault. This
 * means:
 * <ul>
 *   <li>{@code toxicity=1.0} — every connection is affected (deterministic for testing)
 *   <li>{@code toxicity=0.3} — each connection has a 30% independent probability of being
 *       affected; over many connections, approximately 30% will be affected
 *   <li>{@code toxicity=0.0} — no connections are affected (toxic is installed but dormant)
 * </ul>
 *
 * <p>Probabilistic toxicity is useful for simulating flaky infrastructure where faults are
 * intermittent — overloaded upstreams, congested networks, unreliable DNS. However, low-toxicity
 * values require a sufficient number of connections for statistical significance; a test that
 * opens only 1–2 connections with {@code toxicity=0.1} has a ~81% chance of seeing zero failures,
 * making the test non-deterministic.
 *
 * <h2>Available Implementations</h2>
 *
 * <ul>
 *   <li>{@link LatencyToxic} — adds fixed delay ± jitter to data chunks; simulates slow
 *       networks and geo-distribution
 *   <li>{@link TimeoutToxic} — halts data transfer for a configurable duration then closes the
 *       connection; simulates hung upstreams and connection timeouts
 *   <li>{@link BandwidthToxic} — throttles throughput per connection in KB/s; simulates slow
 *       links
 *   <li>{@link SlowCloseToxic} — delays the TCP close after data transfer completes; simulates
 *       connection pool exhaustion and keepalive pressure
 *   <li>{@link LimitDataToxic} — closes the connection after a cumulative byte threshold;
 *       simulates partial reads, truncated responses, and reconnection pressure
 *   <li>{@link DownToxic} — drops all data immediately; simulates complete upstream failure or
 *       probabilistic packet loss
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see AbstractToxic for the shared base implementation
 * @see com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient#addToxic for the wire-level API
 */
public sealed interface ToxicConfig
    permits AbstractToxic {

  /**
   * The unique name of this toxic within its proxy.
   *
   * <p>Toxic names are scoped per-proxy: the same name may exist on different proxies without
   * conflict. Within a single proxy, names must be unique — Toxiproxy uses the name as the key
   * for update ({@code PUT}) and delete ({@code DELETE}) operations. Using a duplicate name
   * when calling {@link com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient#addToxic} will fail
   * at the Toxiproxy API level.
   *
   * <p>Names must not contain characters that break shell command interpolation or JSON string
   * encoding (quotes, backslashes, dollar signs). Restrict to {@code [a-zA-Z0-9_-]}.
   *
   * @return unique toxic name; never null, never blank
   */
  String name();

  /**
   * The Toxiproxy type identifier used in the {@code "type"} field of the REST API payload.
   *
   * <p>Maps to one of Toxiproxy's registered fault types. The value must exactly match the
   * string Toxiproxy expects — a mismatch results in a Toxiproxy API error with HTTP 400.
   * Known values as of Toxiproxy 2.x: {@code "latency"}, {@code "timeout"},
   * {@code "bandwidth"}, {@code "slow_close"}, {@code "limit_data"}, {@code "down"}.
   *
   * <p>Each concrete implementation returns a compile-time constant. The type string is part of
   * the public API contract — changing it is a breaking change.
   *
   * @return Toxiproxy type string; never null, never blank
   */
  String type();

  /**
   * The fraction of connections this toxic is applied to, in the range [0.0, 1.0].
   *
   * <p>Toxiproxy evaluates toxicity as a Bernoulli trial per new connection. See class-level
   * Javadoc for statistical implications of low toxicity values in tests with few connections.
   *
   * @return toxicity probability; always in [0.0, 1.0], inclusive
   */
  double toxicity();

  /**
   * Serializes this toxic's type-specific attributes to a JSON object string for use as the
   * {@code "attributes"} value in the Toxiproxy REST API payload.
   *
   * <p><strong>Returns attributes only — NOT the full payload:</strong> The returned string is
   * the value of the {@code "attributes"} field, not the complete JSON body. For example,
   * {@link LatencyToxic} with 100 ms latency and 10 ms jitter returns:
   * <pre>
   * {"latency":100,"jitter":10}
   * </pre>
   * The complete REST payload constructed by the API client is:
   * <pre>
   * {"name":"my-latency","type":"latency","attributes":{"latency":100,"jitter":10},"toxicity":1.0}
   * </pre>
   *
   * <p><strong>Why attributes-only:</strong> The outer envelope ({@code name}, {@code type},
   * {@code toxicity}) is structurally identical for every toxic type and is assembled by
   * {@link com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl}. Separating responsibilities
   * means each implementation only encodes its own domain knowledge (the Toxiproxy attribute
   * schema for its type) and is not coupled to the REST wire format.
   *
   * <p><strong>Toxics with no attributes:</strong> {@link DownToxic} returns {@code {}} (empty
   * object) because the {@code down} toxic has no configurable attributes in Toxiproxy 2.x.
   *
   * @return JSON object string representing the toxic's attributes; never null; may be empty
   *         object ({@code {}}) for toxics with no attributes
   */
  String toJson();
}
