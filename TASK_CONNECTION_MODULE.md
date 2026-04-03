# Connection Module Enhancement — Task List

**Date:** 2026-04-01  
**Modules:** `macstab-chaos-toxi-core`, `macstab-chaos-connection`, `macstab-chaos-proxy`, `macstab-chaos-core`  
**Branch:** `feat/connection-enhancements`

---

## Phase 1: toxi-core Foundation (shared infrastructure)

All changes here benefit **both** proxy and connection modules.

### Task 1.1 — Stream Direction Support
**Files:** `ToxicConfig`, `AbstractToxic`, all 6 toxic builders, `ToxiproxyApiClient`, `ToxiproxyApiClientImpl`  
**Effort:** 3-4h

- [ ] Add `Stream` enum in `com.macstab.chaos.toxiproxy.toxic`: `UPSTREAM`, `DOWNSTREAM`
- [ ] Add `stream()` method to `ToxicConfig` interface (default: `DOWNSTREAM`)
- [ ] Add `stream` field + builder setter in `AbstractToxic` / `AbstractBuilder`
- [ ] All 6 existing toxic builders get `.upstream()` / `.downstream()` (default: `DOWNSTREAM`, backwards compatible)
- [ ] Add `stream` parameter to `ToxiproxyApiClient.addToxic()` — **new overload**, keep old one as `DOWNSTREAM` delegate for backwards compat
- [ ] Update `ToxiproxyApiClientImpl.buildToxicJson()` to emit `"stream":"upstream"` when upstream, omit when downstream (Toxiproxy default)
- [ ] Tests: verify JSON output with both directions, verify backwards compat (no stream = downstream)

### Task 1.2 — ResetPeerToxic
**Files:** new `ResetPeerToxic.java`, update `AbstractToxic` permits clause  
**Effort:** 1h

- [ ] `ResetPeerToxic extends AbstractToxic` — sealed subclass
- [ ] Attribute: `timeout` (ms before RST sent, ≥ 0, 0 = instant RST)
- [ ] Type string: `"reset_peer"`
- [ ] Builder with `.timeoutMs(int)`, default 0
- [ ] `toJson()` returns `{"timeout":N}`
- [ ] Javadoc: explain difference from `TimeoutToxic` (RST vs graceful close) and `DownToxic` (RST vs silent drop)
- [ ] Tests: builder, validation, JSON output, immutability

### Task 1.3 — SlicerToxic
**Files:** new `SlicerToxic.java`, update `AbstractToxic` permits clause  
**Effort:** 1h

- [ ] `SlicerToxic extends AbstractToxic` — sealed subclass
- [ ] Attributes: `average_size` (bytes, > 0), `size_variation` (bytes, ≥ 0, < average_size), `delay` (ms between slices, ≥ 0)
- [ ] Type string: `"slicer"`
- [ ] Builder with `.averageSizeBytes(int)`, `.sizeVariationBytes(int)`, `.delayMs(int)`
- [ ] Defaults: `average_size=10`, `size_variation=5`, `delay=0`
- [ ] `toJson()` returns `{"average_size":N,"size_variation":N,"delay":N}`
- [ ] Javadoc: explain TCP fragmentation effect, Redis RESP parser stress scenario
- [ ] Tests: builder, validation (variation < average_size), JSON output

### Task 1.4 — updateToxic in API Client
**Files:** `ToxiproxyApiClient`, `ToxiproxyApiClientImpl`  
**Effort:** 1h

- [ ] Add `updateToxic(ctx, proxyName, toxicName, attributes, toxicity)` to `ToxiproxyApiClient`
- [ ] Implement via `PUT /proxies/{proxyName}/toxics/{toxicName}` — Toxiproxy REST endpoint for in-place update
- [ ] JSON payload: `{"attributes":{...},"toxicity":N}` (no name/type needed for update)
- [ ] Tests: verify PUT call, error handling for non-existent toxic

---

## Phase 2: Connection Module — New SPI Methods

### Task 2.1 — ConnectionChaos SPI Extension
**Files:** `ConnectionChaos.java` (in `macstab-chaos-core`)  
**Effort:** 30min

Add to `ConnectionChaos` interface:

```java
/** Truncate connection after N bytes (mid-stream disconnect). */
void truncateConnection(GenericContainer<?> container, String target, long bytes);

/** Remote peer sends TCP RST (crash/firewall simulation). */
void resetByPeer(GenericContainer<?> container, String target, Duration delay);

/** Fragment responses into tiny TCP chunks (parser stress). */
void fragmentResponses(GenericContainer<?> container, String target,
                       int avgSizeBytes, int sizeVariation, Duration delayBetweenChunks);

/** Latency with jitter (realistic network simulation). */
void addLatencyWithJitter(GenericContainer<?> container, String target,
                          Duration latency, Duration jitter);

/** Reset chaos for one target only. */
void reset(GenericContainer<?> container, String target);
```

- [ ] All methods get full Javadoc with use cases and examples
- [ ] Keep existing 6 methods unchanged (backwards compatible)

### Task 2.2 — ToxiproxyConnectionChaos Implementation
**Files:** `ToxiproxyConnectionChaos.java`  
**Effort:** 2-3h

Implement the 5 new SPI methods:

- [ ] `truncateConnection` → creates `LimitDataToxic`, calls `addToxicSafe`
- [ ] `resetByPeer` → creates `ResetPeerToxic`, calls `addToxicSafe`
- [ ] `fragmentResponses` → creates `SlicerToxic`, calls `addToxicSafe`
- [ ] `addLatencyWithJitter` → creates `LatencyToxic` with jitter param, calls `addToxicSafe`
- [ ] `reset(container, target)` → remove single proxy by target address from `ownedProxies` map + cleanup iptables for that target only
- [ ] Validation on all parameters (null checks, range checks, consistent with existing style)
- [ ] Tests for each new method (unit + verify correct toxic type/attributes passed through)

### Task 2.3 — Stream Direction in Connection Module
**Files:** `ToxiproxyConnectionChaos.java`, `ConnectionChaos.java`  
**Effort:** 2h

- [ ] Add direction-aware overloads to `ConnectionChaos` SPI:
  ```java
  void addLatency(GenericContainer<?> container, String target, Duration latency, Stream direction);
  void addLatencyWithJitter(GenericContainer<?> container, String target,
                            Duration latency, Duration jitter, Stream direction);
  void limitBandwidth(GenericContainer<?> container, String target, long bytesPerSecond, Stream direction);
  ```
- [ ] Existing methods (no direction) remain as `DOWNSTREAM` delegates — **zero breaking changes**
- [ ] Connection module passes `stream` through to `addToxicSafe` → API client
- [ ] Toxic names include direction suffix to allow both directions simultaneously: `"latency_upstream"`, `"latency_downstream"`
- [ ] Tests: verify both directions can coexist on same target

---

## Phase 3: Proxy Module — Wire New Capabilities

### Task 3.1 — ProxyChaos SPI Extension
**Files:** `ProxyChaos.java` (in `macstab-chaos-core`), `ProxyChaosProvider.java`  
**Effort:** 2h

- [ ] Add to `ProxyChaos` interface:
  ```java
  void addResetPeer(GenericContainer<?> container, String proxyName, Duration delay);
  void addSlicer(GenericContainer<?> container, String proxyName,
                 int avgSizeBytes, int sizeVariation, Duration delayBetweenChunks);
  ```
- [ ] Add direction overloads for existing methods:
  ```java
  void addLatency(GenericContainer<?> container, String proxyName, Duration latency, Stream direction);
  void limitBandwidth(GenericContainer<?> container, String proxyName, long rateKBps, Stream direction);
  ```
- [ ] Implement in `ProxyChaosProvider` via orchestrator
- [ ] Existing methods unchanged (backwards compatible)
- [ ] Tests

---

## Phase 4: Tests

### Task 4.1 — toxi-core Unit Tests
**Effort:** 2h

- [ ] `ResetPeerToxicTest` — builder, validation, JSON, immutability (same structure as existing toxic tests)
- [ ] `SlicerToxicTest` — builder, validation (size_variation < average_size), JSON
- [ ] `StreamDirectionTest` — all 8 toxics with both stream directions, verify JSON output
- [ ] `ToxiproxyApiClientImplTest` — updateToxic, addToxic with stream parameter

### Task 4.2 — Connection Module Tests
**Effort:** 2h

- [ ] `ToxiproxyConnectionChaosTest` — unit tests for 5 new methods (mock orchestrator)
- [ ] Direction tests — same target with upstream + downstream simultaneously
- [ ] Per-target reset test — verify only target proxy removed, others untouched

### Task 4.3 — Integration Tests
**Effort:** 3h

- [ ] `ResetPeerIntegrationTest` — actual Redis container, verify client receives RST
- [ ] `SlicerIntegrationTest` — actual Redis container, verify fragmented responses still work (Lettuce handles reassembly)
- [ ] `AsymmetricLatencyIntegrationTest` — upstream 0ms, downstream 200ms, verify request fast + response slow
- [ ] `TruncateConnectionIntegrationTest` — verify connection dies after N bytes

---

## Summary

| Phase | Tasks | Effort | What |
|-------|-------|--------|------|
| 1 | 1.1–1.4 | 6-7h | toxi-core: stream direction, 2 new toxics, updateToxic |
| 2 | 2.1–2.3 | 4-5h | Connection module: 5 new methods + direction support |
| 3 | 3.1 | 2h | Proxy module: wire new toxics + direction |
| 4 | 4.1–4.3 | 7h | Tests (unit + integration) |
| **Total** | **10 tasks** | **~19-21h** | |

## Order of Implementation

```
1.1 Stream Direction  ──→  1.2 ResetPeerToxic  ──→  1.3 SlicerToxic  ──→  1.4 updateToxic
                                                                                    │
         ┌──────────────────────────────────────────────────────────────────────────┘
         ↓
2.1 ConnectionChaos SPI  ──→  2.2 Implementation  ──→  2.3 Direction in Connection
         │
         ↓
3.1 ProxyChaos SPI + Implementation
         │
         ↓
4.1 toxi-core tests  ──→  4.2 Connection tests  ──→  4.3 Integration tests
```

## Rules

- **ASK before executing any code changes**
- **ASK before committing**
- All methods < 40 lines
- 90%+ test coverage
- Backwards compatible — zero breaking changes to existing APIs
- Sealed class permits update requires modifying `AbstractToxic` permits clause (from 6 to 8 subclasses)
