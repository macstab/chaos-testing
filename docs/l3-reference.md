# L3 Incident Annotation Reference

L3 annotations are the most powerful feature of the Macstab Chaos Testing Framework. Each one
reproduces the **exact failure profile of a named production incident** — combining network,
DNS, memory, JVM, and filesystem chaos into a single annotation.

No other chaos testing tool provides this abstraction. Every entry below corresponds to a real
incident documented in post-mortems, GitHub issues, or engineering blogs.

**Required setup for L3:**

```java

@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})  // declare domains used
class MyTest {
    @Container
    @AppContainer
    static GenericContainer<?> app = ...;

    @Test
    @IncidentChaosJvmCarrierPinning
        // one annotation → named incident
    void test() { ...}
}
```

**Severity legend:**

- ⚠️ `CRITICAL` — causes complete service outage or data loss
- 🔶 `SEVERE` — significant degradation, requires immediate action
- 🔷 `MODERATE` — noticeable impact, degrades gracefully

---

## Redis (`macstab-chaos-testpacks-l3-redis`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-redis:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-redis</artifactId>`

### `@IncidentChaosRedisFailoverStorm` — ⚠️ CRITICAL

**Reproduces:** Redis Sentinel election storm combined with an in-flight NTP clock correction.
Clients see connection-refused errors, transient DNS failures, and a wall-clock jump that
invalidates Sentinel quorum timers simultaneously — the triple-fault that turns a routine
failover into a full cluster split.

**Industry reference:** Documented by multiple high-traffic Redis deployments where NTP
corrections during failover caused Sentinel quorum timers to fire simultaneously, triggering
cascading re-elections on an already-recovering cluster.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at configured toxicity — rejects client connections while the new primary is not
  yet accepting
- DNS: EAI_AGAIN on every forward lookup — Sentinel member address re-resolution transiently fails during IP rebinding
- Time: REALTIME clock skew forward by `clockSkewMs` ms — NTP correction corrupts Sentinel timeout arithmetic and WAIT()
  deadline calculations

| Attribute     | Type     | Default | Description                                                     |
|---------------|----------|---------|-----------------------------------------------------------------|
| `id`          | `String` | `""`    | Container filter; empty = all containers                        |
| `toxicity`    | `double` | `0.5`   | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0) |
| `clockSkewMs` | `long`   | `3000`  | Milliseconds by which the realtime clock is shifted forward     |

```java

@Test
@IncidentChaosRedisFailoverStorm(toxicity = 0.8, clockSkewMs = 5000L)
void redis_survivesFailoverStorm() { ...}
```

---

### `@IncidentChaosRedisNetworkFlap` — ⚠️ CRITICAL

**Reproduces:** Redis Sentinel election storm caused by rapid TCP reset cycling. Both CONNECT
and RECV operations are hit with ECONNRESET at high toxicity, causing clients to perceive the
master as changing every ~200 ms — every in-flight command must be retried against the newly
elected primary.

**Industry reference:** Observed in high-throughput Redis deployments where network instability
triggers cascading promotions faster than clients can converge on a stable master address.

**Composed of:**

- Connection: CONNECT → ECONNRESET at `toxicity` — new connections are abruptly reset, triggering Sentinel election on
  every connect attempt
- Connection: RECV → ECONNRESET at `toxicity` — established connections cut mid-stream, causing clients to re-resolve
  the Sentinel topology

| Attribute  | Type     | Default | Description                                                            |
|------------|----------|---------|------------------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                               |
| `toxicity` | `double` | `0.9`   | Fraction of CONNECT and RECV syscalls that return ECONNRESET (0.0–1.0) |

```java

@Test
@IncidentChaosRedisNetworkFlap(toxicity = 0.9)
void redis_survivesNetworkFlap() { ...}
```

---

### `@IncidentChaosRedisCacheAvalanche` — ⚠️ CRITICAL

**Reproduces:** Redis cache avalanche from mass simultaneous key expiry. Every client request
misses the cache; latency on new connections slows re-population; the backing store absorbs
full read traffic with no cache shielding.

**Industry reference:** Mass Redis key expiry is a well-documented failure mode from e-commerce
black-friday post-mortems: keys set with the same TTL (loaded at startup) expire simultaneously,
causing thundering-herd against the database.

**Composed of:**

- Connection: CONNECT latency of `latencyMs` ms — slows Redis connection establishment, delaying cache re-population
  under load
- JVM: `replaceReturn(NULL)` on methods matching `classPattern` — forces cache lookup methods to return null, simulating
  100% cache miss at the Java layer

| Attribute      | Type     | Default   | Description                                                                    |
|----------------|----------|-----------|--------------------------------------------------------------------------------|
| `id`           | `String` | `""`      | Container filter; empty = all containers                                       |
| `toxicity`     | `double` | `0.8`     | Fraction of CONNECT syscalls subjected to artificial latency (0.0–1.0)         |
| `latencyMs`    | `long`   | `50`      | Milliseconds of latency injected into each Redis connection attempt            |
| `classPattern` | `String` | `"redis"` | Class name prefix used to match cache client methods for null-return injection |

```java

@Test
@IncidentChaosRedisCacheAvalanche(toxicity = 0.9, latencyMs = 100L)
void redis_survivesCacheAvalanche() { ...}
```

---

### `@IncidentChaosRedisOomEviction` — 🔶 SEVERE

**Reproduces:** Compound failure when Redis hits its `maxmemory` limit. Aggressive eviction
causes clients to receive connection resets; the application JVM and OS both encounter memory
pressure from defensive buffering and retry allocation.

**Industry reference:** Redis maxmemory eviction storms are documented in Redis documentation
(maxmemory-policy) and operator post-mortems, particularly in deployments where client-side
buffering amplifies host memory pressure once Redis starts returning -OOM errors.

**Composed of:**

- Memory: MMAP_ANON → ENOMEM at probability `probability` — anonymous memory allocations fail, reproducing host-side OOM
  accompanying Redis eviction pressure
- Connection: CONNECT → ECONNRESET at toxicity `toxicity` — Redis forcibly closes connections during eviction storms to
  shed load
- JVM: OutOfMemoryError injected on methods matching class prefix `"redis"` — simulates Java heap impact of defensive
  copy-on-eviction read patterns

| Attribute     | Type     | Default | Description                                                            |
|---------------|----------|---------|------------------------------------------------------------------------|
| `id`          | `String` | `""`    | Container filter; empty = all containers                               |
| `toxicity`    | `double` | `0.6`   | Fraction of CONNECT syscalls that return ECONNRESET (0.0–1.0)          |
| `probability` | `double` | `0.7`   | Probability that an anonymous mmap allocation returns ENOMEM (0.0–1.0) |

```java

@Test
@IncidentChaosRedisOomEviction(toxicity = 0.7, probability = 0.8)
void redis_survivesOomEviction() { ...}
```

---

### `@IncidentChaosRedisClockDrift` — 🔷 MODERATE

**Reproduces:** Clock drift between the application node and the Redis node causing TTL
corruption, premature key expiry, and CAS failure storms from `WATCH()`/`EVAL` scripts
that rely on consistent wall-clock ordering.

**Industry reference:** Redis EVAL script TTL drift and WATCH() CAS failures under clock skew
are documented in the Redis FAQ and observed in containerised deployments running on shared
hypervisors where NTP is not tightly configured.

**Composed of:**

- Time: REALTIME offset of `skewMs` ms at probability `probability` — shifts the application clock forward, causing TTL
  calculations and script deadlines to diverge from Redis server time
- Connection: SEND latency of `latencyMs` ms — adds wire delay that amplifies apparent skew for time-sensitive
  MULTI/EXEC and WATCH() transactions

| Attribute     | Type     | Default | Description                                                            |
|---------------|----------|---------|------------------------------------------------------------------------|
| `id`          | `String` | `""`    | Container filter; empty = all containers                               |
| `skewMs`      | `long`   | `500`   | Milliseconds by which the realtime clock is skewed forward             |
| `probability` | `double` | `1.0`   | Probability (0.0–1.0) that the clock offset is applied to a given call |
| `latencyMs`   | `long`   | `20`    | Milliseconds of latency injected into every SEND syscall               |

```java

@Test
@IncidentChaosRedisClockDrift(skewMs = 1000L, latencyMs = 30L)
void redis_survivesClockDrift() { ...}
```

---

### `@IncidentChaosRedisSlowlog` — 🔷 MODERATE

**Reproduces:** Redis slow-log command backlog where individual commands exceed the
`slowlog-log-slower-than` threshold, causing the server's single-threaded event loop to queue
subsequent commands and eventually time out callers.

**Industry reference:** Classic single-threaded Redis failure mode, documented in the Redis
SLOWLOG documentation and observable whenever KEYS, SORT, or large LRANGE commands are mixed
with latency-sensitive request paths.

**Composed of:**

- Connection: RECV latency of `latencyMs` ms on every receive syscall — slows response delivery, mirroring slow command
  execution from the client's perspective
- JVM: SocketTimeoutException injected at METHOD_ENTER on methods matching `classPattern` — causes Redis client method
  calls to fail with a timeout before data arrives

| Attribute      | Type     | Default   | Description                                                                |
|----------------|----------|-----------|----------------------------------------------------------------------------|
| `id`           | `String` | `""`      | Container filter; empty = all containers                                   |
| `latencyMs`    | `long`   | `500`     | Milliseconds of latency injected into each Redis RECV syscall              |
| `classPattern` | `String` | `"redis"` | Class name prefix used to match Redis client methods for timeout injection |

```java

@Test
@IncidentChaosRedisSlowlog(latencyMs = 800L)
void redis_survivesSlowlog() { ...}
```

---

## JDBC / Database (`macstab-chaos-testpacks-l3-jdbc`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-jdbc:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-jdbc</artifactId>`

### `@IncidentChaosJdbcConnectionStorm` — ⚠️ CRITICAL

**Reproduces:** JDBC connection pool exhaustion storm under a load spike. Every
connection-acquire attempt is refused at the network level while the JVM layer simultaneously
reports pool exhaustion, driving request queues to saturation.

**Industry reference:** Described in HikariCP documentation §"Pool sizing", the Percona blog
"Diagnosing Connection Pool Exhaustion", and numerous post-mortems from high-traffic e-commerce
deployments.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at `toxicity` — rejects new TCP connections to the database, preventing the pool
  from opening replacement connections
- JVM: `SQLException("connection pool exhausted")` on classes matching `classPattern` at METHOD_ENTER — surfaces pool
  exhaustion at the Java layer before the socket layer even attempts to connect

| Attribute      | Type     | Default  | Description                                                                 |
|----------------|----------|----------|-----------------------------------------------------------------------------|
| `id`           | `String` | `""`     | Container filter; empty = all containers                                    |
| `toxicity`     | `double` | `0.7`    | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)             |
| `classPattern` | `String` | `"jdbc"` | Class name prefix used to match JDBC client methods for exception injection |

```java

@Test
@IncidentChaosJdbcConnectionStorm(toxicity = 0.9, classPattern = "com.example.repo")
void db_survivesConnectionStorm() { ...}
```

---

### `@IncidentChaosJdbcPrimaryFailover` — ⚠️ CRITICAL

**Reproduces:** JDBC primary-failover event with DNS-based failover lag. The primary database
goes down, replica promotion is in progress, and DNS transiently fails while connections are
still refused by the new primary that has not yet fully taken over.

**Industry reference:** AWS RDS Multi-AZ failover documentation, the Percona blog "MySQL Failover
Benchmarks", and numerous post-mortems describing DNS-based failover causing transient blips.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at `toxicity` — new connections refused while the replica is being promoted
- DNS: EAI_AGAIN on every forward lookup — transient DNS flap during IP rebinding after replica promotion
- JVM: `SQLTransientConnectionException("primary failover in progress")` on classes matching `classPattern` at
  METHOD_ENTER — surfaces failover state at the Java layer

| Attribute      | Type     | Default  | Description                                                                 |
|----------------|----------|----------|-----------------------------------------------------------------------------|
| `id`           | `String` | `""`     | Container filter; empty = all containers                                    |
| `toxicity`     | `double` | `0.8`    | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)             |
| `classPattern` | `String` | `"jdbc"` | Class name prefix used to match JDBC client methods for exception injection |

```java

@Test
@IncidentChaosJdbcPrimaryFailover(toxicity = 0.9)
void db_survivesPrimaryFailover() { ...}
```

---

### `@IncidentChaosJdbcNetworkPartition` — ⚠️ CRITICAL

**Reproduces:** Network partition during active two-phase commit (2PC). POLL-level timeouts
model the partition itself while EPIPE errors on SEND simulate severed connections, leaving
in-doubt transactions in an indeterminate state with split-brain risk.

**Industry reference:** Gray & Lamport "Consensus on Transaction Commit", PostgreSQL documentation
§"Two-Phase Transactions", and post-mortems from microservice architectures using XA transactions
across multiple databases.

**Composed of:**

- Connection: POLL timeout of `timeoutMs` ms at `toxicity` — simulates a network partition cutting the TCP path
- Connection: SEND → EPIPE at `toxicity` — broken-pipe errors on active send operations model severed connections inside
  open transactions
- JVM: `SQLException("transaction aborted — network partition")` on classes matching `classPattern` at METHOD_ENTER —
  surfaces partition state to the application layer

| Attribute      | Type     | Default  | Description                                                                 |
|----------------|----------|----------|-----------------------------------------------------------------------------|
| `id`           | `String` | `""`     | Container filter; empty = all containers                                    |
| `toxicity`     | `double` | `0.8`    | Fraction of POLL/SEND syscalls subjected to the partition fault (0.0–1.0)   |
| `timeoutMs`    | `long`   | `5000`   | Milliseconds for the POLL timeout, modelling the partition window           |
| `classPattern` | `String` | `"jdbc"` | Class name prefix used to match JDBC client methods for exception injection |

```java

@Test
@IncidentChaosJdbcNetworkPartition(toxicity = 0.9, timeoutMs = 3000L)
void db_survivesNetworkPartition() { ...}
```

---

### `@IncidentChaosJdbcWalPressure` — 🔶 SEVERE

**Reproduces:** WAL fsync pressure causing commit timeouts and replica lag accumulation.
Slow fsync and write operations on the database data directory combine with network receive
latency to model an overloaded storage subsystem during heavy write workloads.

**Industry reference:** PostgreSQL documentation §"WAL Configuration", the PostgreSQL wiki
"Tuning Your PostgreSQL Server", and post-mortems from heavy OLTP workloads describing how
slow storage devices cause commit storms.

**Composed of:**

- Filesystem: FSYNC latency of `fsyncDelayMs` ms on `dataPath` — delays WAL segment flushing, causing commits to block
  until timeout
- Filesystem: WRITE latency of `fsyncDelayMs/2` ms on `dataPath` — models saturated write throughput slowing page writes
  alongside WAL
- Connection: RECV latency of `fsyncDelayMs` ms at toxicity 1.0 — simulates back-pressure from the database as it falls
  behind under write load
- JVM: `SQLTimeoutException("WAL sync timeout exceeded")` on classes matching `classPattern` at METHOD_ENTER — surfaces
  commit timeout at the Java layer

| Attribute      | Type     | Default                 | Description                                                                     |
|----------------|----------|-------------------------|---------------------------------------------------------------------------------|
| `id`           | `String` | `""`                    | Container filter; empty = all containers                                        |
| `fsyncDelayMs` | `long`   | `500`                   | Milliseconds of latency injected into FSYNC and WRITE syscalls on the data path |
| `dataPath`     | `String` | `"/var/lib/postgresql"` | Absolute path prefix for the database data directory to target with I/O latency |
| `classPattern` | `String` | `"jdbc"`                | Class name prefix used to match JDBC client methods for exception injection     |

```java

@Test
@IncidentChaosJdbcWalPressure(fsyncDelayMs = 800L, dataPath = "/var/lib/postgresql")
void db_survivesWalPressure() { ...}
```

---

### `@IncidentChaosJdbcDiskFull` — 🔶 SEVERE

**Reproduces:** Disk-full condition on the database data directory during bulk writes or
migrations. ENOSPC errors on WRITE and FSYNC operations cause transactions to fail
mid-execution with potential for partial data and corruption risk.

**Industry reference:** PostgreSQL documentation §"Monitoring Disk Usage", MySQL documentation
§"Disk Full in MySQL", and post-mortems from data-migration incidents where unexpected volume
growth exhausted provisioned storage.

**Composed of:**

- Filesystem: WRITE → ENOSPC at `probability` on `path` — bulk inserts and WAL writes fail with "no space left on
  device"
- Filesystem: FSYNC → ENOSPC at `probability` on `path` — commit flushes fail, causing transactions to be rolled back or
  left in indeterminate state
- JVM: `SQLException("disk full — write failed")` on classes matching `classPattern` at METHOD_ENTER — surfaces
  disk-full state at the Java layer

| Attribute      | Type     | Default                 | Description                                                                       |
|----------------|----------|-------------------------|-----------------------------------------------------------------------------------|
| `id`           | `String` | `""`                    | Container filter; empty = all containers                                          |
| `path`         | `String` | `"/var/lib/postgresql"` | Absolute path prefix for the database data directory to target with ENOSPC faults |
| `probability`  | `double` | `0.9`                   | Fraction of WRITE and FSYNC syscalls that return ENOSPC (0.0–1.0)                 |
| `classPattern` | `String` | `"jdbc"`                | Class name prefix used to match JDBC client methods for exception injection       |

```java

@Test
@IncidentChaosJdbcDiskFull(path = "/var/lib/postgresql", probability = 0.95)
void db_survivesDiskFull() { ...}
```

---

### `@IncidentChaosJdbcSequenceIdJump` — 🔶 SEVERE

**Reproduces:** Postgres sequence pre-allocation gap after failover. When the primary fails and
the replica is promoted, the sequence cache in the old primary is lost and the new primary
pre-allocates a fresh block, causing IDs to jump by 32–64, breaking pagination and dense-ID
assumptions.

**Industry reference:** Postgres documentation §"Sequence Manipulation Functions". The incident.io
2025 post-mortem describes an ID jump of 32 after primary failover causing downstream pagination
and constraint violations in a production system.

**Composed of:**

- JVM: DataIntegrityViolationException on class prefix `classPattern` at METHOD_EXIT — injected at the JDBC connection
  acquire path to reproduce application-level handling of sequence gaps after failover reconnect

| Attribute      | Type     | Default                      | Description                                                                             |
|----------------|----------|------------------------------|-----------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                         | Container filter; empty = all containers                                                |
| `classPattern` | `String` | `"org.springframework.jdbc"` | Class name prefix used to match JDBC connection acquire methods for exception injection |

```java

@Test
@IncidentChaosJdbcSequenceIdJump(classPattern = "org.springframework.jdbc")
void db_survivesSequenceIdJump() { ...}
```

---

## HTTP (`macstab-chaos-testpacks-l3-http`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-http:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-http</artifactId>`

### `@IncidentChaosHttpCascadingTimeout` — ⚠️ CRITICAL

**Reproduces:** Timeout cascade across a service call chain. Each hop exhausts its deadline
budget waiting for the next, so a single slow upstream propagates full timeout failures through
every caller in the chain.

**Industry reference:** Netflix Hystrix post-mortem literature and AWS re:Invent sessions on
microservice resilience — each hop's timeout is shorter than the sum of downstream timeouts,
making cascades structurally inevitable without bulkheads.

**Composed of:**

- Connection: TCP timeout of `latencyMs` ms on all endpoints — the primary socket-level delay that burns the request
  timeout budget
- DNS: forward lookup latency of `latencyMs/2` ms — pre-connection DNS delay further reduces the effective timeout
  margin
- JVM: SocketTimeoutException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the application-level
  exception thrown when combined latency exceeds the configured HTTP client timeout

| Attribute      | Type     | Default  | Description                                                                         |
|----------------|----------|----------|-------------------------------------------------------------------------------------|
| `id`           | `String` | `""`     | Container filter; empty = all containers                                            |
| `latencyMs`    | `long`   | `3000`   | Milliseconds applied as TCP timeout; DNS latency is set to half this value          |
| `classPattern` | `String` | `"http"` | Class name prefix used to match HTTP client methods for timeout exception injection |

```java

@Test
@IncidentChaosHttpCascadingTimeout(latencyMs = 5000L)
void http_survivesCascadingTimeout() { ...}
```

---

### `@IncidentChaosHttpRetryAmplification` — ⚠️ CRITICAL

**Reproduces:** Retry amplification storm where connection-refused errors on a fraction of
requests cause HTTP clients to retry, multiplying effective load on the already-failing upstream
until full request fan-out saturates every available connection.

**Industry reference:** Amazon's "Exponential Backoff and Jitter" blog post and Google's SRE
book chapter on cascading failures — retries without jitter are a primary amplification mechanism
in microservice outages.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at toxicity `toxicity` — initial failures that trigger client retry logic
- JVM: IOException injected at METHOD_ENTER on class prefix `classPattern` — forces the client library's retry path to
  activate, amplifying the load multiplier

| Attribute      | Type     | Default  | Description                                                                   |
|----------------|----------|----------|-------------------------------------------------------------------------------|
| `id`           | `String` | `""`     | Container filter; empty = all containers                                      |
| `toxicity`     | `double` | `0.5`    | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)               |
| `classPattern` | `String` | `"http"` | Class name prefix used to match HTTP client methods for IOException injection |

```java

@Test
@IncidentChaosHttpRetryAmplification(toxicity = 0.5)
void http_survivesRetryAmplification() { ...}
```

---

### `@IncidentChaosHttpPartialOutage` — 🔶 SEVERE

**Reproduces:** Partial backend outage where a fraction of instances are unreachable while
others serve traffic. DNS resolution transiently fails for affected pods, producing a mixed
healthy/failing response profile that exposes load-balancer and circuit-breaker logic to
realistic split conditions.

**Industry reference:** Stripe and GitHub post-mortems — the mixed signal confuses monitoring
thresholds and delays incident detection compared to clean full-outage events.

**Composed of:**

- Connection: CONNECT → ETIMEDOUT at toxicity `toxicity` — connection attempts to the affected fraction of backends time
  out
- DNS: EAI_AGAIN on every forward lookup — transient DNS failures simulate pod-level endpoint deregistration races
- JVM: ConnectException injected at METHOD_ENTER on class prefix `classPattern` — ensures the application's exception
  handling for mixed-success scenarios is exercised

| Attribute      | Type     | Default  | Description                                                                        |
|----------------|----------|----------|------------------------------------------------------------------------------------|
| `id`           | `String` | `""`     | Container filter; empty = all containers                                           |
| `toxicity`     | `double` | `0.5`    | Fraction of CONNECT syscalls that return ETIMEDOUT (0.0–1.0)                       |
| `classPattern` | `String` | `"http"` | Class name prefix used to match HTTP client methods for ConnectException injection |

```java

@Test
@IncidentChaosHttpPartialOutage(toxicity = 0.5)
void http_survivesPartialOutage() { ...}
```

---

### `@IncidentChaosHttpSslHandshakeStorm` — 🔶 SEVERE

**Reproduces:** TLS negotiation failures under load. Connection resets during the TCP handshake
phase abort SSL/TLS sessions before cipher negotiation completes, reproducing the failure mode
seen when certificates expire, cipher suites mismatch, or a TLS terminator is overwhelmed.

**Industry reference:** Cloudflare and Let's Encrypt post-mortems around certificate renewal
failures and Java application incidents triggered by JDK cipher-suite policy changes between
minor versions.

**Composed of:**

- Connection: CONNECT → ECONNRESET at toxicity `toxicity` — TCP resets abort the TLS handshake at the socket level,
  mimicking mid-handshake RST packets
- JVM: SSLHandshakeException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the JSSE exception
  thrown when TLS negotiation fails

| Attribute      | Type     | Default           | Description                                                                         |
|----------------|----------|-------------------|-------------------------------------------------------------------------------------|
| `id`           | `String` | `""`              | Container filter; empty = all containers                                            |
| `toxicity`     | `double` | `0.7`             | Fraction of CONNECT syscalls that return ECONNRESET (0.0–1.0)                       |
| `classPattern` | `String` | `"javax.net.ssl"` | Class name prefix used to match SSL/TLS methods for SSLHandshakeException injection |

```java

@Test
@IncidentChaosHttpSslHandshakeStorm(toxicity = 0.7)
void http_survivesSslHandshakeStorm() { ...}
```

---

## gRPC (`macstab-chaos-testpacks-l3-grpc`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-grpc:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-grpc</artifactId>`

### `@IncidentChaosGrpcDeadlinePropagation` — ⚠️ CRITICAL

**Reproduces:** gRPC deadline cascade where each hop in a service chain inherits a shorter
deadline budget from the caller. RECV latency delays response delivery while a negative
monotonic clock skew causes deadline checks to fire late, compounding the propagation failure
across the entire call chain.

**Industry reference:** gRPC documentation §"Deadlines", the Google SRE book §"Handling
Overload", and post-mortems from microservice deployments where a single slow downstream hop
caused cascading DEADLINE_EXCEEDED across an entire service mesh.

**Composed of:**

- Connection: RECV latency of `latencyMs` ms at toxicity 1.0 — delays gRPC response frames, consuming the deadline
  budget before the response arrives
- Time: MONOTONIC clock skew of `-skewMs` ms at probability 1.0 — slow monotonic makes deadline checks fire late,
  masking imminent expiry until the budget is fully consumed
- JVM: `StatusRuntimeException("DEADLINE_EXCEEDED: deadline propagation failure")` on classes matching `classPattern` at
  METHOD_ENTER — triggers DEADLINE_EXCEEDED at the Java layer

| Attribute      | Type     | Default     | Description                                                                 |
|----------------|----------|-------------|-----------------------------------------------------------------------------|
| `id`           | `String` | `""`        | Container filter; empty = all containers                                    |
| `latencyMs`    | `long`   | `2000`      | Milliseconds of RECV latency injected into gRPC response frames             |
| `skewMs`       | `long`   | `500`       | Milliseconds by which the monotonic clock is shifted backward (slow clock)  |
| `classPattern` | `String` | `"io.grpc"` | Class name prefix used to match gRPC client methods for exception injection |

```java

@Test
@IncidentChaosGrpcDeadlinePropagation(latencyMs = 3000L, skewMs = 800L)
void grpc_survivesDeadlinePropagation() { ...}
```

---

### `@IncidentChaosGrpcGoawayStorm` — 🔶 SEVERE

**Reproduces:** gRPC GOAWAY storm caused by `maxConnectionAge` cycling. The server
continuously sends GOAWAY frames to drain connections, which manifest as ECONNRESET on
in-flight RECV operations, producing a steady 10–20 UNAVAILABLE errors/hour as documented in
grpc-java #9566.

**Industry reference:** grpc-java issue #9566 and Envoy documentation §"Connection Management".
Clients that do not implement transparent retry on GOAWAY see a continuous background rate of
UNAVAILABLE errors proportional to their RPC rate and the configured `maxConnectionAge`.

**Composed of:**

- Connection: RECV → ECONNRESET at `toxicity` — GOAWAY closes in-flight streams; active RPCs are aborted mid-response
- JVM: `StatusRuntimeException(UNAVAILABLE)` on class prefix `classPattern` at METHOD_EXIT — surfaces GOAWAY-caused
  UNAVAILABLE at the gRPC stub layer

| Attribute      | Type     | Default     | Description                                                               |
|----------------|----------|-------------|---------------------------------------------------------------------------|
| `id`           | `String` | `""`        | Container filter; empty = all containers                                  |
| `toxicity`     | `double` | `0.6`       | Fraction of RECV syscalls that return ECONNRESET (0.0–1.0)                |
| `classPattern` | `String` | `"io.grpc"` | Class name prefix used to match gRPC stub methods for exception injection |

```java

@Test
@IncidentChaosGrpcGoawayStorm(toxicity = 0.6, classPattern = "io.grpc")
void grpc_survivesGoawayStorm() { ...}
```

---

### `@IncidentChaosGrpcConnectionDrain` — 🔶 SEVERE

**Reproduces:** gRPC server connection drain during a rolling deploy. Servers send GOAWAY frames
which manifest as connection resets, while a drain-lag delay on SEND operations models the
graceful-drain window where in-flight RPCs must complete before the connection is closed.

**Industry reference:** gRPC documentation §"Graceful Server Shutdown", Envoy documentation
§"Draining", and post-mortems from Kubernetes rolling update incidents where clients did not
handle GOAWAY correctly.

**Composed of:**

- Connection: CONNECT → ECONNRESET at `toxicity` — GOAWAY frames cause new connection attempts to be reset as the server
  drains
- Connection: SEND latency of 50 ms at `toxicity` — drain-lag delay models in-flight RPCs completing slowly
- JVM: `StatusRuntimeException("UNAVAILABLE: server draining connection")` on classes matching `classPattern` at
  METHOD_ENTER — surfaces drain state to the application layer

| Attribute      | Type     | Default     | Description                                                                 |
|----------------|----------|-------------|-----------------------------------------------------------------------------|
| `id`           | `String` | `""`        | Container filter; empty = all containers                                    |
| `toxicity`     | `double` | `0.6`       | Fraction of CONNECT and SEND syscalls subjected to drain faults (0.0–1.0)   |
| `classPattern` | `String` | `"io.grpc"` | Class name prefix used to match gRPC client methods for exception injection |

```java

@Test
@IncidentChaosGrpcConnectionDrain(toxicity = 0.7)
void grpc_survivesConnectionDrain() { ...}
```

---

### `@IncidentChaosGrpcLoadBalancingFailure` — 🔶 SEVERE

**Reproduces:** gRPC DNS-based load-balancing failure. Kubernetes service DNS flap and SRV
record resolution lag cause the name resolver to retry-storm the DNS server while new connection
attempts are simultaneously refused by backends that are not yet reachable.

**Industry reference:** gRPC documentation §"Load Balancing in gRPC", Kubernetes documentation
§"DNS for Services and Pods", and post-mortems from Kubernetes deployments where headless
service SRV record churn during rolling updates caused extended TRANSIENT_FAILURE periods.

**Composed of:**

- DNS: EAI_AGAIN on every forward lookup — DNS name resolver retry storm; every `getaddrinfo()` call fails transiently
- DNS: getaddrinfo latency of 500 ms on every forward lookup — slow DNS resolution delays each retry
- Connection: CONNECT → ECONNREFUSED at `toxicity` — backends refuse connections during DNS-based LB convergence
- JVM: `StatusRuntimeException("UNAVAILABLE: load balancer name resolution failure")` on classes matching `classPattern`
  at METHOD_ENTER — surfaces LB failure to the application layer

| Attribute      | Type     | Default     | Description                                                                 |
|----------------|----------|-------------|-----------------------------------------------------------------------------|
| `id`           | `String` | `""`        | Container filter; empty = all containers                                    |
| `toxicity`     | `double` | `0.5`       | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)             |
| `classPattern` | `String` | `"io.grpc"` | Class name prefix used to match gRPC client methods for exception injection |

```java

@Test
@IncidentChaosGrpcLoadBalancingFailure(toxicity = 0.6)
void grpc_survivesLoadBalancingFailure() { ...}
```

---

## Kafka (`macstab-chaos-testpacks-l3-kafka`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kafka:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-kafka</artifactId>`

### `@IncidentChaosKafkaBrokerFailure` — ⚠️ CRITICAL

**Reproduces:** Complete Kafka broker failure: all new connections are refused, DNS resolution
of broker addresses transiently fails, and the application JVM receives TimeoutExceptions from
the Kafka producer/consumer client, triggering the exponential-backoff retry storm.

**Industry reference:** Broker down → producer/consumer retry storm with exponential backoff
collision is a well-documented Kafka failure mode. Producers exhaust `buffer.memory` before the
broker recovers; consumers trigger rebalances as `max.poll.interval.ms` is exceeded waiting
for fetch responses.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at toxicity `toxicity` — broker port not accepting; producer metadata requests and
  consumer fetches all fail immediately
- DNS: EAI_AGAIN on every forward lookup — bootstrap server address re-resolution fails during the reconnect window
- JVM: TimeoutException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the application-level
  symptom of a producer retry storm under broker absence

| Attribute      | Type     | Default              | Description                                                                  |
|----------------|----------|----------------------|------------------------------------------------------------------------------|
| `id`           | `String` | `""`                 | Container filter; empty = all containers                                     |
| `toxicity`     | `double` | `0.8`                | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)              |
| `classPattern` | `String` | `"org.apache.kafka"` | Class name prefix used to match Kafka client methods for exception injection |

```java

@Test
@IncidentChaosKafkaBrokerFailure(toxicity = 0.9, classPattern = "org.apache.kafka")
void kafka_survivesBrokerFailure() { ...}
```

---

### `@IncidentChaosKafkaZookeeperLoss` — ⚠️ CRITICAL

**Reproduces:** Loss of the ZooKeeper metadata service causing Kafka metadata unavailability.
DNS hard-fails for all ZooKeeper hostname lookups and connection attempts are refused — brokers
cannot elect a controller, partition leadership is frozen, and producers block indefinitely.

**Industry reference:** Without ZooKeeper quorum, brokers cannot elect a controller, partition
leadership is frozen, and producers block until `delivery.timeout.ms` is exceeded.

**Composed of:**

- DNS: EAI_FAIL on all forward lookups — ZooKeeper hostname cannot be resolved; metadata bootstrap from brokers fails
  immediately
- Connection: CONNECT → ECONNREFUSED at `toxicity` — even cached addresses are refused; producers block waiting for
  metadata

| Attribute  | Type     | Default | Description                                                     |
|------------|----------|---------|-----------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                        |
| `toxicity` | `double` | `0.9`   | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0) |

```java

@Test
@IncidentChaosKafkaZookeeperLoss(toxicity = 0.9)
void kafka_survivesZookeeperLoss() { ...}
```

---

### `@IncidentChaosKafkaUncleanLeaderElection` — ⚠️ CRITICAL

**Reproduces:** Unclean leader election caused by ISR replication lag. High RECV latency causes
the in-sync replica set to shrink, resulting in a lagged broker being promoted when the current
leader fails — the elected leader may be missing messages acknowledged by the old leader, causing
permanent data loss of 500+ messages.

**Industry reference:** Kafka documentation §"Replication" and §"Leader election". When
`unclean.leader.election.enable=true`, the elected leader may be missing messages that were
acknowledged by the old leader, causing permanent data loss.

**Composed of:**

- Connection: RECV latency of `latencyMs` ms — simulates ISR replication lag causing brokers to fall out of the in-sync
  replica set
- JVM: TimeoutException on class prefix `classPattern` at METHOD_EXIT — models producer/consumer timeout as the lagged
  broker is promoted

| Attribute      | Type     | Default              | Description                                                                             |
|----------------|----------|----------------------|-----------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                 | Container filter; empty = all containers                                                |
| `latencyMs`    | `long`   | `500`                | RECV latency in milliseconds simulating ISR replication lag                             |
| `classPattern` | `String` | `"org.apache.kafka"` | Class name prefix used to match Kafka producer/consumer methods for exception injection |

```java

@Test
@IncidentChaosKafkaUncleanLeaderElection(latencyMs = 500L, classPattern = "org.apache.kafka")
void kafka_survivesUncleanLeaderElection() { ...}
```

---

### `@IncidentChaosKafkaStoragePressure` — 🔶 SEVERE

**Reproduces:** Kafka broker disk filling causing log segment write slowdown, fsync failures
with EIO to prevent data corruption, and produce request back-off as the broker cannot
acknowledge batches — ultimately causing consumers to rebalance as producers block.

**Industry reference:** Broker disk fills → log segment cleanup lag → producer backpressure →
consumer rebalance is a documented Kafka operational hazard. Multiple post-mortems cite
unmonitored disk growth from high-retention topics as the root cause.

**Composed of:**

- Filesystem: WRITE latency of `latencyMs` ms on path `path` — log segment appends slow down; broker append throughput
  falls
- Filesystem: FSYNC → EIO at probability `probability` on path `path` — segment cleanup and index fsync fail; unclean
  segment closure triggers recovery on restart
- Connection: SEND latency of `latencyMs/2` ms — produce requests are delayed from the network layer, compounding
  storage-side backpressure

| Attribute     | Type     | Default             | Description                                                                   |
|---------------|----------|---------------------|-------------------------------------------------------------------------------|
| `id`          | `String` | `""`                | Container filter; empty = all containers                                      |
| `path`        | `String` | `"/var/kafka/data"` | Absolute path prefix of the Kafka data directory to apply filesystem rules to |
| `latencyMs`   | `long`   | `300`               | Milliseconds of write latency injected into log segment appends               |
| `probability` | `double` | `0.3`               | Probability (0.0–1.0) that an fsync on the data path returns EIO              |

```java

@Test
@IncidentChaosKafkaStoragePressure(path = "/var/kafka/data", latencyMs = 500L)
void kafka_survivesStoragePressure() { ...}
```

---

### `@IncidentChaosKafkaConsumerRebalance` — 🔶 SEVERE

**Reproduces:** GC pause causing a consumer to exceed `max.poll.interval.ms`. The broker sees
the consumer as dead, triggers a group rebalance, and the consumer rejoins — potentially
re-processing messages already committed by other members.

**Industry reference:** Confluent Kafka consumer tuning guide and post-mortems from JVM-based
consumers running mixed workloads with large heap allocations.

**Composed of:**

- JVM: RuntimeException injected on class prefix `classPattern` — simulates the GC pause that blocks the poll loop from
  returning within the deadline
- Connection: RECV latency of `gcPauseMs` ms on every receive syscall — simulates the broker-side view of a consumer
  that stops sending heartbeats during the pause window

| Attribute      | Type     | Default              | Description                                                                    |
|----------------|----------|----------------------|--------------------------------------------------------------------------------|
| `id`           | `String` | `""`                 | Container filter; empty = all containers                                       |
| `gcPauseMs`    | `long`   | `5000`               | Milliseconds of simulated GC pause injected into RECV and via JVM exception    |
| `classPattern` | `String` | `"org.apache.kafka"` | Class name prefix used to match Kafka consumer methods for exception injection |

```java

@Test
@IncidentChaosKafkaConsumerRebalance(gcPauseMs = 8000L)
void kafka_survivesConsumerRebalance() { ...}
```

---

### `@IncidentChaosKafkaClockDrift` — 🔷 MODERATE

**Reproduces:** Clock skew between a Kafka broker and its clients causing timestamp-based
partition routing failures, inconsistent log compaction decisions, and offset commit drift.

**Industry reference:** KIP-32 and operator post-mortems involving NTP desynchronisation on
broker or client hosts, where messages land in wrong partition segments and compaction may evict
live data.

**Composed of:**

- Time: REALTIME clock skew of `skewMs` ms at probability `probability` — broker and client wall-clocks diverge;
  timestamp-indexed segments become inconsistent
- Connection: RECV latency of 20 ms on every receive syscall — adds network jitter compounding with clock skew
- JVM: TimestampException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the client-level error
  raised when timestamp routing fails under skew

| Attribute      | Type     | Default              | Description                                                                  |
|----------------|----------|----------------------|------------------------------------------------------------------------------|
| `id`           | `String` | `""`                 | Container filter; empty = all containers                                     |
| `skewMs`       | `long`   | `1000`               | Milliseconds by which the realtime clock is shifted forward                  |
| `probability`  | `double` | `1.0`                | Probability (0.0–1.0) that the clock offset is applied                       |
| `classPattern` | `String` | `"org.apache.kafka"` | Class name prefix used to match Kafka client methods for exception injection |

```java

@Test
@IncidentChaosKafkaClockDrift(skewMs = 2000L, probability = 1.0)
void kafka_survivesClockDrift() { ...}
```

---

### `@IncidentChaosKafkaNetworkDegradation` — 🔷 MODERATE

**Reproduces:** Sustained network degradation between producers/consumers and the Kafka cluster.
Bidirectional latency causes produce requests to time out, fetch requests to stall, and consumer
lag to build until a rebalance is triggered.

**Industry reference:** Documented in Confluent operator guides as a trigger for cascading lag
buildup in cloud-hosted Kafka deployments during network congestion events.

**Composed of:**

- Connection: SEND latency of `latencyMs` ms — produce requests and metadata fetches are delayed; `request.timeout.ms`
  violations accumulate in high-throughput paths
- Connection: RECV latency of `latencyMs` ms — fetch responses arrive late; consumer lag grows as the fetch loop falls
  behind
- JVM: NetworkException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the client-level error
  logged when sustained latency exceeds the configured request timeout

| Attribute      | Type     | Default              | Description                                                                  |
|----------------|----------|----------------------|------------------------------------------------------------------------------|
| `id`           | `String` | `""`                 | Container filter; empty = all containers                                     |
| `latencyMs`    | `long`   | `200`                | Milliseconds of latency injected into each SEND and RECV syscall             |
| `classPattern` | `String` | `"org.apache.kafka"` | Class name prefix used to match Kafka client methods for exception injection |

```java

@Test
@IncidentChaosKafkaNetworkDegradation(latencyMs = 500L)
void kafka_survivesNetworkDegradation() { ...}
```

---

## Spring Boot (`macstab-chaos-testpacks-l3-spring-boot`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-spring-boot:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-spring-boot</artifactId>`

### `@IncidentChaosSpringStartupFailure` — ⚠️ CRITICAL

**Reproduces:** Compound Spring Boot startup failure in a Kubernetes environment. DNS resolution
races during init container startup, backing services refuse connections, anonymous memory
allocations fail under node pressure, and the ApplicationContext throws an exception — causing
the startup probe to fail and the pod to enter a CrashLoopBackOff cycle.

**Industry reference:** Kubernetes startup probe failure loops caused by DNS resolution races at
pod start are a well-known pattern in microservice deployments. Init container cycles block
readiness gates; combined with node memory pressure from over-provisioned pods, startup failures
cascade.

**Composed of:**

- DNS: EAI_AGAIN on every forward lookup — service discovery for database, config server, and messaging brokers fails
  transiently during the DNS propagation window at pod start
- Connection: CONNECT → ECONNREFUSED at toxicity `toxicity` — downstream services are not yet reachable; Spring's
  `@Bean` initialisation fails on first connection
- Memory: MMAP_ANON → ENOMEM at probability `probability` — node memory pressure causes anonymous JVM allocations to
  fail during class loading and context refresh
- JVM: ApplicationContextException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the failure when
  the ApplicationContext cannot complete its refresh

| Attribute      | Type     | Default                 | Description                                                                    |
|----------------|----------|-------------------------|--------------------------------------------------------------------------------|
| `id`           | `String` | `""`                    | Container filter; empty = all containers                                       |
| `toxicity`     | `double` | `0.5`                   | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)                |
| `probability`  | `double` | `0.3`                   | Probability that an anonymous mmap allocation returns ENOMEM (0.0–1.0)         |
| `classPattern` | `String` | `"org.springframework"` | Class name prefix used to match Spring context methods for exception injection |

```java

@Test
@IncidentChaosSpringStartupFailure(toxicity = 0.6, probability = 0.4)
void spring_survivesStartupFailure() { ...}
```

---

### `@IncidentChaosSpringDatabaseOutage` — ⚠️ CRITICAL

**Reproduces:** Complete database outage as seen by a Spring Boot application. The database
host refuses new connections, DNS resolution fails transiently, and the application JVM receives
a DataAccessResourceFailureException — the expected trigger for Resilience4j circuit breaker
opening and readiness probe failure.

**Industry reference:** Database outage → circuit breaker pattern → readiness probe failure is
the canonical Resilience4j + Spring Boot Actuator failure scenario, documented in the Resilience4j
Spring Boot starter guide and Spring Boot Actuator health indicator documentation.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at toxicity `toxicity` — database port not accepting; all JDBC connection pool
  acquisition attempts fail
- DNS: EAI_AGAIN on every forward lookup — database hostname resolution fails transiently; pool validation checks cannot
  resolve the host
- JVM: DataAccessResourceFailureException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the
  Spring Data exception raised when the data source is unreachable

| Attribute      | Type     | Default                 | Description                                                                            |
|----------------|----------|-------------------------|----------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                    | Container filter; empty = all containers                                               |
| `toxicity`     | `double` | `0.9`                   | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)                        |
| `classPattern` | `String` | `"org.springframework"` | Class name prefix used to match Spring Data repository methods for exception injection |

```java

@Test
@IncidentChaosSpringDatabaseOutage(toxicity = 0.95)
void spring_survivesDatabaseOutage() { ...}
```

---

### `@IncidentChaosSpringMemoryCrisis` — ⚠️ CRITICAL

**Reproduces:** Sustained memory exhaustion in a Spring Boot JVM. Both anonymous and
file-backed memory mappings fail at a configured rate, and OutOfMemoryError is injected via the
JVM chaos layer to reproduce the application-level symptom of heap exhaustion preceding an OOM kill.

**Industry reference:** Sustained memory leak → OOM kill → pod restart with in-flight request
loss → circuit breaker opens is a documented failure mode for long-running Spring Boot services
without heap size caps or proper native memory accounting.

**Composed of:**

- Memory: MMAP_ANON → ENOMEM at probability `pressureRate` — anonymous JVM heap allocations fail; GC cannot reclaim fast
  enough
- Memory: MMAP → ENOMEM at probability `pressureRate` — file-backed memory mappings also fail; NIO buffers and class
  loading see allocation failures
- JVM: OutOfMemoryError injected at METHOD_ENTER on class prefix `classPattern` — reproduces the error seen by the
  application when heap is exhausted

| Attribute      | Type     | Default                 | Description                                                                           |
|----------------|----------|-------------------------|---------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                    | Container filter; empty = all containers                                              |
| `pressureRate` | `double` | `0.7`                   | Probability that mmap (anonymous and file-backed) allocations return ENOMEM (0.0–1.0) |
| `classPattern` | `String` | `"org.springframework"` | Class name prefix used to match Spring methods for OutOfMemoryError injection         |

```java

@Test
@IncidentChaosSpringMemoryCrisis(pressureRate = 0.8)
void spring_survivesMemoryCrisis() { ...}
```

---

### `@IncidentChaosSpringConfigServerDown` — 🔶 SEVERE

**Reproduces:** Spring Cloud Config Server outage causing stale configuration propagation.
DNS hard-fails for all forward lookups, connection attempts time out, and the application JVM
receives a ConnectException during the config refresh cycle — causing the service to continue
with stale configuration and potentially divergent feature flag state.

**Industry reference:** Spring Cloud Config Server availability is a single point of failure
for config refresh in multi-service deployments. Post-mortems document feature flag divergence
and blue/green split traffic when some pods receive stale configs while others successfully
refresh.

**Composed of:**

- DNS: EAI_FAIL on every forward lookup — the config server hostname cannot be resolved; Spring Cloud Config client
  cannot establish a connection
- Connection: timeout at `timeoutMs` ms at toxicity `toxicity` — connection attempts hang until the configured timeout
  expires; refresh cycles block application threads
- JVM: ConnectException injected at METHOD_ENTER on class prefix `classPattern` — reproduces the exception raised by the
  config client when the server is unreachable

| Attribute      | Type     | Default                       | Description                                                                                |
|----------------|----------|-------------------------------|--------------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                          | Container filter; empty = all containers                                                   |
| `toxicity`     | `double` | `0.8`                         | Fraction of connection attempts to the config server that time out (0.0–1.0)               |
| `timeoutMs`    | `long`   | `5000`                        | Milliseconds before a connection attempt to the config server times out                    |
| `classPattern` | `String` | `"org.springframework.cloud"` | Class name prefix used to match Spring Cloud config client methods for exception injection |

```java

@Test
@IncidentChaosSpringConfigServerDown(toxicity = 0.9, timeoutMs = 3000L)
void spring_survivesConfigServerDown() { ...}
```

---

### `@IncidentChaosSpringGracefulShutdown` — 🔶 SEVERE

**Reproduces:** Spring Boot graceful shutdown race under live traffic. SIGTERM is received while
active requests are in-flight, the drain window is extended by both network latency and thread
join lag, and the Kubernetes grace period expires before all requests complete — causing the pod
to be forcibly killed with active connections aborted.

**Industry reference:** Spring Boot lifecycle docs and Kubernetes operator guides. The drain window
(`spring.lifecycle.timeout-per-shutdown-phase`) must exceed the longest request latency; this
scenario tests whether the application correctly handles the case where it does not.

**Composed of:**

- JVM: AsyncRequestTimeoutException injected at METHOD_ENTER on class prefix `classPattern` — active requests straddling
  the shutdown boundary exceed their async timeout
- Connection: RECV latency of `drainMs` ms on every receive syscall — in-flight requests take longer to receive upstream
  responses during the drain window
- Process: PTHREAD_CREATE latency of `drainMs/2` ms — thread join during container shutdown is delayed, extending the
  executor pool drain time

| Attribute      | Type     | Default                 | Description                                                                       |
|----------------|----------|-------------------------|-----------------------------------------------------------------------------------|
| `id`           | `String` | `""`                    | Container filter; empty = all containers                                          |
| `drainMs`      | `long`   | `10000`                 | Milliseconds of in-flight request drain time to simulate on RECV and thread joins |
| `classPattern` | `String` | `"org.springframework"` | Class name prefix used to match Spring web methods for exception injection        |

```java

@Test
@IncidentChaosSpringGracefulShutdown(drainMs = 15000L)
void spring_survivesGracefulShutdown() { ...}
```

---

## JVM Internals (`macstab-chaos-testpacks-l3-jvm`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-jvm:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-jvm</artifactId>`

> **These scenarios are unique in the industry.** No other chaos testing tool exposes
> JVM-internal failure modes as testable annotations. Scenarios such as carrier pinning,
> code cache exhaustion, GCLocker-induced spurious OOM, and JIT deoptimisation storms are
> completely invisible to standard monitoring — no exceptions, no alerts, no heap dumps —
> yet they cause complete service outages or sustained performance collapse.
>
> Requires the `chaos-testing-java-agent` on the test classpath.

---

### `@IncidentChaosJvmCarrierPinning` — ⚠️ CRITICAL

**Reproduces:** JVM virtual thread carrier pinning. `synchronized` blocks in libraries such as
Jedis or older JDBC drivers pin carrier threads, starving the ForkJoinPool carrier pool and
preventing virtual threads from being scheduled. The service appears to hang with zero errors,
zero rejections, and zero warnings in logs.

**Industry reference:** A known JDK 21 production pattern where third-party libraries hold
monitors during I/O, starving the ForkJoinPool carrier pool and causing a full service hang
without any error output. JFR VirtualThreadPinned events are the only observable signal.

**Composed of:**

- JVM: VirtualThreadCarrierPinning with `pinnedThreadCount` carriers held inside synthetic `synchronized` blocks for
  `pinDurationMs` ms each, continuously

| Attribute           | Type     | Default | Description                                                 |
|---------------------|----------|---------|-------------------------------------------------------------|
| `id`                | `String` | `""`    | Container filter; empty = all containers                    |
| `pinnedThreadCount` | `int`    | `4`     | Number of carrier threads to pin simultaneously             |
| `pinDurationMs`     | `long`   | `100`   | Duration in milliseconds each carrier thread is held pinned |

```java

@Test
@IncidentChaosJvmCarrierPinning(pinnedThreadCount = 4, pinDurationMs = 200L)
void jvm_survivesCarrierPinning() { ...}
```

---

### `@IncidentChaosJvmG1ToSpaceExhausted` — ⚠️ CRITICAL

**Reproduces:** G1 GC "to-space exhausted" evacuation failure under heap pressure. The heap is
pre-filled to near capacity while a sustained allocation rate prevents G1 from completing normal
evacuation, forcing a full stop-the-world GC with 5–30x longer pause times. Liveness probes kill
the pod mid-GC.

**Industry reference:** A documented G1 failure mode where insufficient survivor space during
mixed or young GC causes evacuation failure, triggering a full STW collection. Frequently observed
in large-heap services (>8 GB) under sustained write-heavy workloads.

**Composed of:**

- JVM: HeapPressure retaining `heapFillMb` MB of live objects in 1 MB chunks
- JVM: GcPressure at `allocationRateMbPerSec` MB/s for 30 s cycles to keep G1 busy
- JVM: OutOfMemoryError injection on `com.` classes at METHOD_EXIT — reproduces application-visible heap exhaustion

| Attribute                | Type     | Default | Description                                                 |
|--------------------------|----------|---------|-------------------------------------------------------------|
| `id`                     | `String` | `""`    | Container filter; empty = all containers                    |
| `heapFillMb`             | `int`    | `256`   | Megabytes of heap to retain as live objects (fills old-gen) |
| `allocationRateMbPerSec` | `long`   | `50`    | Allocation rate in MB/s to sustain GC pressure              |

```java

@Test
@IncidentChaosJvmG1ToSpaceExhausted(heapFillMb = 512, allocationRateMbPerSec = 100L)
void jvm_survivesG1ToSpaceExhausted() { ...}
```

---

### `@IncidentChaosJvmCodeCacheFull` — ⚠️ CRITICAL

**Reproduces:** JVM code cache exhaustion where the JIT compiler fills the native code cache
with synthetic compiled methods until it reaches capacity and shuts down permanently. All
subsequent application code runs in the interpreter, causing a 10–50x throughput collapse with
zero exceptions thrown.

**Industry reference:** The Atlassian Confluence CodeCache full incident (2019) caused a complete
service performance collapse with no alerts, no errors, and a gradual degradation that looked like
a memory leak in monitoring but had a clean heap.

**Composed of:**

- JVM: CodeCachePressure with `classCount` classes × `methodsPerClass` methods, each JIT-compiled approximately 15,000
  times to maximise cache consumption

| Attribute         | Type     | Default | Description                                             |
|-------------------|----------|---------|---------------------------------------------------------|
| `id`              | `String` | `""`    | Container filter; empty = all containers                |
| `classCount`      | `int`    | `500`   | Number of synthetic classes to generate and JIT-compile |
| `methodsPerClass` | `int`    | `100`   | Number of methods per synthetic class                   |

```java

@Test
@IncidentChaosJvmCodeCacheFull(classCount = 500, methodsPerClass = 100)
void jvm_survivesCodeCacheFull() { ...}
```

---

### `@IncidentChaosJvmSafepointCascade` — ⚠️ CRITICAL

**Reproduces:** JVM safepoint cascade where a GC safepoint pause triggers simultaneous timeout
storms in every connected system. During the STW pause, in-flight sockets time out, DNS
re-resolutions fail, and connection pool health checks expire — all at the same moment the JVM
resumes.

**Industry reference:** Documented in multiple Kafka and ZooKeeper production incidents where a
single long GC pause caused Sentinel/leader election storms, connection pool exhaustion, and
cascading retry amplification across the dependent service mesh.

**Composed of:**

- JVM: SafepointStorm every `gcIntervalMs` ms — forces stop-the-world pauses that make all downstream timeouts fire
  simultaneously on resume
- Connection: RECV → ECONNRESET at `toxicity` — HikariCP, Kafka producers, and ZooKeeper clients lose their sockets
  during the pause window
- DNS: EAI_AGAIN on every forward lookup — ZooKeeper session renewal and service discovery re-resolution fail
  transiently

| Attribute      | Type     | Default | Description                                                |
|----------------|----------|---------|------------------------------------------------------------|
| `id`           | `String` | `""`    | Container filter; empty = all containers                   |
| `gcIntervalMs` | `long`   | `100`   | Interval between forced safepoints in milliseconds         |
| `toxicity`     | `double` | `0.7`   | Fraction of RECV syscalls that return ECONNRESET (0.0–1.0) |

```java

@Test
@IncidentChaosJvmSafepointCascade(gcIntervalMs = 200L, toxicity = 0.8)
void jvm_survivesSafepointCascade() { ...}
```

---

### `@IncidentChaosJvmMetaspaceGlacier` — 🔶 SEVERE

**Reproduces:** Classloader leak that exhausts Metaspace over hours. Synthetic classes are
generated and loaded with strong references retained so they can never be GC'd. Heap metrics
remain green throughout; the problem is only discovered at the eventual Metaspace OOM.

**Industry reference:** Frequently observed in OSGi containers, application servers doing dynamic
class generation (Hibernate, CGLIB, ByteBuddy), and plugin-loading frameworks that do not properly
unload classloaders on reload/undeploy cycles.

**Composed of:**

- JVM: MetaspacePressure with `generatedClassCount` classes × `fieldsPerClass` fields, strong references retained (
  classloader leak mode)

| Attribute             | Type     | Default | Description                                                                   |
|-----------------------|----------|---------|-------------------------------------------------------------------------------|
| `id`                  | `String` | `""`    | Container filter; empty = all containers                                      |
| `generatedClassCount` | `int`    | `1000`  | Number of synthetic classes to generate and retain strongly                   |
| `fieldsPerClass`      | `int`    | `50`    | Number of fields per generated class (controls per-class metaspace footprint) |

```java

@Test
@IncidentChaosJvmMetaspaceGlacier(generatedClassCount = 1000, fieldsPerClass = 50)
void jvm_survivesMetaspaceGlacier() { ...}
```

---

### `@IncidentChaosJvmGcLockerFakeOom` — 🔶 SEVERE

**Reproduces:** GCLocker-induced spurious OutOfMemoryError. Monitor contention prevents threads
from releasing JNI critical sections, which blocks GC from running while sustained allocation
pressure triggers the GC overhead limit — causing `OutOfMemoryError: GC overhead limit exceeded`
even though the heap is not actually exhausted.

**Industry reference:** The CleverTap 2021 incident where JNI library calls holding critical
sections blocked G1 GC, triggering GC overhead limit OOMs under normal heap utilisation. The
service restarted cleanly every time, masking the root cause.

**Composed of:**

- JVM: GcPressure at `allocationRateMbPerSec` MB/s for 30 s cycles
- JVM: MonitorContention with `contendingThreads` threads holding a synthetic lock for `lockHoldMs` ms — models JNI
  critical section contention that delays GC
- JVM: OutOfMemoryError injection (GC overhead limit exceeded) on `com.` classes at METHOD_EXIT — reproduces the
  spurious OOM visible to the application

| Attribute                | Type     | Default | Description                                                              |
|--------------------------|----------|---------|--------------------------------------------------------------------------|
| `id`                     | `String` | `""`    | Container filter; empty = all containers                                 |
| `allocationRateMbPerSec` | `long`   | `100`   | Allocation rate in MB/s to drive GC overhead pressure                    |
| `lockHoldMs`             | `long`   | `50`    | Duration in milliseconds each contending thread holds the synthetic lock |
| `contendingThreads`      | `int`    | `10`    | Number of threads competing for the synthetic lock                       |

```java

@Test
@IncidentChaosJvmGcLockerFakeOom(allocationRateMbPerSec = 150L, lockHoldMs = 75L, contendingThreads = 12)
void jvm_survivesGcLockerFakeOom() { ...}
```

---

### `@IncidentChaosJvmDeoptimizationStorm` — 🔶 SEVERE

**Reproduces:** JIT deoptimisation storm caused by class retransformation (JVMTI). Retransformation
invalidates all compiled code for the affected classes, forcing the JIT to deoptimise and recompile
on the next invocation. Repeated at high frequency this creates a continuous CPU spike and
throughput collapse.

**Industry reference:** Observed in APM/instrumentation agent interactions (Datadog, Dynatrace)
where agent class retransformation during peak traffic caused repeated deopt storms, CPU throttling,
and P99 latency spikes that resolved without intervention.

**Composed of:**

- JVM: SafepointStorm every `gcIntervalMs` ms with retransformation of `retransformClassCount` classes per cycle — each
  retransformation invalidates previously compiled native code and triggers deoptimisation safepoints

| Attribute               | Type     | Default | Description                                         |
|-------------------------|----------|---------|-----------------------------------------------------|
| `id`                    | `String` | `""`    | Container filter; empty = all containers            |
| `gcIntervalMs`          | `long`   | `200`   | Interval between safepoint cycles in milliseconds   |
| `retransformClassCount` | `int`    | `50`    | Number of classes retransformed per safepoint cycle |

```java

@Test
@IncidentChaosJvmDeoptimizationStorm(gcIntervalMs = 300L, retransformClassCount = 100)
void jvm_survivesDeoptimizationStorm() { ...}
```

---

### `@IncidentChaosJvmDirectMemoryLeak` — 🔶 SEVERE

**Reproduces:** Netty/gRPC off-heap ByteBuffer exhaustion. Direct memory is allocated via NIO
`ByteBuffer.allocateDirect()` without a `Cleaner` reference, preventing GC reclamation. Once
the direct memory limit is reached, every new NIO or Netty allocation fails.

**Industry reference:** A common pattern in services using Netty, gRPC-Java, or Kafka clients
where reference counting bugs or missing buffer releases cause gradual off-heap exhaustion
invisible to standard heap monitoring.

**Composed of:**

- JVM: DirectBufferPressure allocating `totalMb` MB total in `bufferSizeMb` MB chunks, retained without Cleaner (leak
  mode)

| Attribute      | Type     | Default | Description                                                   |
|----------------|----------|---------|---------------------------------------------------------------|
| `id`           | `String` | `""`    | Container filter; empty = all containers                      |
| `totalMb`      | `int`    | `256`   | Total direct memory to allocate and retain in megabytes       |
| `bufferSizeMb` | `int`    | `1`     | Size of each individual direct buffer allocation in megabytes |

```java

@Test
@IncidentChaosJvmDirectMemoryLeak(totalMb = 512, bufferSizeMb = 4)
void jvm_survivesDirectMemoryLeak() { ...}
```

---

## Kubernetes (`macstab-chaos-testpacks-l3-kubernetes`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kubernetes:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-kubernetes</artifactId>`

### `@IncidentChaosK8sRollingUpdateRst` — ⚠️ CRITICAL

**Reproduces:** iptables endpoint removal lag during every Kubernetes rolling update. Old pod IPs
stay in iptables rules for ~30 seconds after the pod terminates, causing TCP RST on all in-flight
requests that were routed to the terminating pod.

**Industry reference:** Slack engineering blog: 73% of incidents traced to deploy events.
Kubernetes networking documentation acknowledges iptables propagation lag (~30 seconds) as a
known source of connection resets during rolling updates.

**Composed of:**

- Connection: RECV → ECONNRESET at `toxicity` — models the TCP RST injected by the kernel when packets arrive at a
  socket that has already been torn down

| Attribute  | Type     | Default | Description                                                |
|------------|----------|---------|------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                   |
| `toxicity` | `double` | `0.3`   | Fraction of RECV syscalls that return ECONNRESET (0.0–1.0) |

```java

@Test
@IncidentChaosK8sRollingUpdateRst(toxicity = 0.3)
void k8s_survivesRollingUpdateRst() { ...}
```

---

### `@IncidentChaosK8sOomKillMidGc` — ⚠️ CRITICAL

**Reproduces:** G1 GC temporarily exceeding the configured `-Xmx` during heap evacuation,
pushing RSS above the cgroup memory limit. The kernel OOM killer terminates the JVM with exit
code 137 mid-GC — no Java `OutOfMemoryError` appears in logs, leading engineers to spend hours
looking for a Java-level root cause that does not exist.

**Industry reference:** G1 heap evacuation failure causing cgroup RSS breach is documented in
multiple JVM and Kubernetes operator post-mortems. The symptom — exit 137 with no Java stack
trace — is a well-known diagnostic gap in containerised JVM deployments.

**Composed of:**

- Memory: OOM-kill at `toxicity` — simulates the cgroup RSS limit breach that triggers the kernel OOM killer during a G1
  evacuation pause
- JVM: OutOfMemoryError injection on application classes — models heap exhaustion visible to application code just
  before the OS-level kill arrives

| Attribute  | Type     | Default | Description                                                             |
|------------|----------|---------|-------------------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                                |
| `toxicity` | `double` | `0.8`   | Probability (0.0–1.0) that the OOM killer fires on any given allocation |

```java

@Test
@IncidentChaosK8sOomKillMidGc(toxicity = 0.8)
void k8s_survivesOomKillMidGc() { ...}
```

---

### `@IncidentChaosK8sCpuThrottleGcAmplification` — ⚠️ CRITICAL

**Reproduces:** CFS CPU throttle / GC amplification loop where a Kubernetes CPU limit causes
the CFS scheduler to throttle all JVM GC threads simultaneously. A 50 ms GC pause becomes
400 ms wall-clock time. The Kubernetes liveness probe times out and kills the pod.

**Industry reference:** CFS bandwidth throttling and its interaction with JVM GC thread counts
is documented in Netflix, LinkedIn, and Booking.com engineering posts. 71% of Kubernetes
deployments with CPU limits experience this; liveness probes kill healthy pods under transient
load, causing cascading restart loops.

**Composed of:**

- JVM: SafepointStorm every `gcIntervalMs` ms — drives frequent GC safepoints that are then extended by CFS throttle
  into multi-hundred-millisecond pauses
- Connection: RECV → timeout at `toxicity` — models downstream services timing out while waiting for responses from the
  throttled JVM

| Attribute      | Type     | Default | Description                                        |
|----------------|----------|---------|----------------------------------------------------|
| `id`           | `String` | `""`    | Container filter; empty = all containers           |
| `gcIntervalMs` | `long`   | `100`   | Interval between forced safepoints in milliseconds |
| `toxicity`     | `double` | `0.4`   | Fraction of RECV syscalls that time out (0.0–1.0)  |

```java

@Test
@IncidentChaosK8sCpuThrottleGcAmplification(gcIntervalMs = 100L, toxicity = 0.4)
void k8s_survivesCpuThrottleGcAmplification() { ...}
```

---

### `@IncidentChaosK8sDnsNdots5Storm` — 🔶 SEVERE

**Reproduces:** Kubernetes ndots:5 DNS storm where every external hostname lookup triggers four
NXDOMAIN search-domain queries before the resolver attempts the bare name. Under load this
overwhelms CoreDNS, producing 5–20 second DNS timeouts.

**Industry reference:** kubernetes/kubernetes issue #56903 documents the ndots:5 default as a
known source of DNS amplification. Weave, Cloudflare, and multiple Kubernetes operators have
published post-mortems tracing production outages to this behaviour.

**Composed of:**

- DNS: EAI_AGAIN on every forward lookup — models the transient NXDOMAIN/SERVFAIL responses from an overloaded CoreDNS
  under ndots:5 amplification
- DNS: latency of `latencyMs` ms on every forward lookup — models the 5–20 second timeout window before CoreDNS
  autoscales

| Attribute   | Type     | Default | Description                                                   |
|-------------|----------|---------|---------------------------------------------------------------|
| `id`        | `String` | `""`    | Container filter; empty = all containers                      |
| `latencyMs` | `long`   | `5000`  | DNS latency in milliseconds to inject on every forward lookup |

```java

@Test
@IncidentChaosK8sDnsNdots5Storm(latencyMs = 5000L)
void k8s_survivesDnsNdots5Storm() { ...}
```

---

### `@IncidentChaosK8sSidecarShutdownRace` — 🔶 SEVERE

**Reproduces:** Kubernetes sidecar shutdown race condition. Kubernetes sends SIGTERM to all
containers in a pod simultaneously. The Envoy or istio-proxy sidecar closes its listeners before
the application container has finished draining, causing all outbound calls made during the drain
window to receive ECONNREFUSED.

**Industry reference:** Istio and Envoy documentation acknowledge the simultaneous SIGTERM as a
known race. Multiple Kubernetes service mesh post-mortems describe ECONNREFUSED spikes during
deploys that correlate exactly with sidecar termination order.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at `toxicity` — models outbound connections refused by the already-closed
  Envoy/istio-proxy sidecar listener

| Attribute  | Type     | Default | Description                                                     |
|------------|----------|---------|-----------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                        |
| `toxicity` | `double` | `0.5`   | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0) |

```java

@Test
@IncidentChaosK8sSidecarShutdownRace(toxicity = 0.5)
void k8s_survivesSidecarShutdownRace() { ...}
```

---

## Cache (`macstab-chaos-testpacks-l3-cache`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-cache:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-cache</artifactId>`

### `@IncidentChaosCacheStampede` — ⚠️ CRITICAL

**Reproduces:** Cache stampede where a hot cache key expires and 100–1,000x concurrent requests
race to the backing database simultaneously. DB lock contention prevents the cache from
refilling, which causes the next wave of requests to hit the DB again — a death spiral observed
at Twitter, Reddit, and Instagram scale.

**Industry reference:** Twitter, Reddit, and Instagram have all published post-mortems describing
thundering-herd events triggered by simultaneous expiry of popular cache keys under high read
traffic.

**Composed of:**

- Connection: RECV latency of `latencyMs` ms at `toxicity` — simulates DB slowdown under thundering-herd load
- JVM: `corruptReturnValue(NULL)` on `classPattern` at METHOD_EXIT — forces continuous cache misses so every request
  falls through to the backing store

| Attribute      | Type     | Default                       | Description                                                                            |
|----------------|----------|-------------------------------|----------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                          | Container filter; empty = all containers                                               |
| `latencyMs`    | `long`   | `2000`                        | RECV latency in milliseconds injected on the backing-store connection                  |
| `toxicity`     | `double` | `0.9`                         | Fraction of RECV syscalls that are delayed (0.0–1.0)                                   |
| `classPattern` | `String` | `"org.springframework.cache"` | Class-name prefix used to select the cache abstraction layer for null-return injection |

```java

@Test
@IncidentChaosCacheStampede(latencyMs = 3000L, toxicity = 0.95)
void cache_survivesCacheStampede() { ...}
```

---

### `@IncidentChaosCacheWarmingFailure` — ⚠️ CRITICAL

**Reproduces:** Cold-start cache warming failure after a deployment or restart. The cache is
empty so every request falls through to a backend that was sized for cached load, not full
request volume. The backend becomes overwhelmed, the cache cannot warm up, and the failure
becomes self-sustaining.

**Industry reference:** Netflix publicly described this class of incident and re-engineered their
cache warm-up flow with pre-warming and traffic shaping to prevent cold-start backend overload.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at `toxicity` — cache is unreachable during the warm-up window, forcing every
  lookup to bypass the cache
- Connection: RECV latency of `latencyMs` ms at 1.0 toxicity — surviving connections to the backend are slow,
  compounding backend exhaustion

| Attribute   | Type     | Default | Description                                                           |
|-------------|----------|---------|-----------------------------------------------------------------------|
| `id`        | `String` | `""`    | Container filter; empty = all containers                              |
| `toxicity`  | `double` | `0.7`   | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)       |
| `latencyMs` | `long`   | `1000`  | RECV latency in milliseconds applied to surviving backend connections |

```java

@Test
@IncidentChaosCacheWarmingFailure(toxicity = 0.8, latencyMs = 1500L)
void cache_survivesCacheWarmingFailure() { ...}
```

---

### `@IncidentChaosHazelcastSplitBrain` — ⚠️ CRITICAL

**Reproduces:** Hazelcast split-brain event where a network partition causes cluster members to
split into two independent partitions that each continue accepting writes. When the partition
heals, the merge strategy silently discards one side's writes, causing data loss with no
exception visible to the application.

**Industry reference:** Hazelcast split-brain merge policies are documented in the Hazelcast
reference manual. Production incidents involving split-brain data loss have been reported across
multiple cloud environments where network ACL changes caused asymmetric partition events.

**Composed of:**

- Connection: RECV → ECONNRESET at `toxicity` — disrupts member-to-member heartbeats, causing partition detection and
  split into two independent clusters
- DNS: EAI_AGAIN on any forward lookup — disrupts member discovery during the partition, preventing automatic re-join

| Attribute  | Type     | Default | Description                                                |
|------------|----------|---------|------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                   |
| `toxicity` | `double` | `0.5`   | Fraction of RECV syscalls that return ECONNRESET (0.0–1.0) |

```java

@Test
@IncidentChaosHazelcastSplitBrain(toxicity = 0.6)
void cache_survivesHazelcastSplitBrain() { ...}
```

---

### `@IncidentChaosCacheSerializationMismatch` — 🔶 SEVERE

**Reproduces:** Rolling-deploy serialisation mismatch where old Redis entries written by the
previous pod version fail deserialisation on the new pod version, causing 100% cache misses
on new pods until all old entries expire.

**Industry reference:** Spring Boot issue #38959 documents this pattern, triggered during rolling
upgrades when cached object graphs change without explicit cache invalidation or versioned key
strategies.

**Composed of:**

- JVM: `InvalidClassException("serialVersionUID mismatch")` on `classPattern` at METHOD_EXIT — every cache read on the
  new pods throws during deserialisation, forcing the call through to the backing store

| Attribute      | Type     | Default                 | Description                                                                              |
|----------------|----------|-------------------------|------------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                    | Container filter; empty = all containers                                                 |
| `classPattern` | `String` | `"org.springframework"` | Class-name prefix used to select the cache deserialisation layer for exception injection |

```java

@Test
@IncidentChaosCacheSerializationMismatch(classPattern = "com.example.cache")
void cache_survivesCacheSerializationMismatch() { ...}
```

---

### `@IncidentChaosCaffeineEvictionDeadlock` — 🔶 SEVERE

**Reproduces:** Caffeine eviction deadlock (Caffeine issue #672) where a single slow cache
loader holds the internal `evictionLock` for the duration of its load. Any other thread that
triggers eviction on an unrelated key blocks on the same lock, causing up to 1,400 threads to
wait. Cache throughput drops to zero and a restart is required to recover.

**Industry reference:** Caffeine GitHub issue #672 documents this behaviour: a single slow loader
acquires `evictionLock` and blocks all eviction-driven operations across the entire cache
instance.

**Composed of:**

- Connection: RECV latency of `latencyMs` ms at 1.0 toxicity — makes one loader slow, keeping the eviction lock held for
  the entire wait duration
- JVM: `TimeoutException("Caffeine loader timed out")` on `classPattern` at METHOD_EXIT — loader fails after holding the
  lock, amplifying the thread pile-up

| Attribute      | Type     | Default                          | Description                                                                              |
|----------------|----------|----------------------------------|------------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                             | Container filter; empty = all containers                                                 |
| `latencyMs`    | `long`   | `500`                            | RECV latency in milliseconds that keeps the slow loader (and the eviction lock) occupied |
| `classPattern` | `String` | `"com.github.benmanes.caffeine"` | Class-name prefix used to select Caffeine loader methods for exception injection         |

```java

@Test
@IncidentChaosCaffeineEvictionDeadlock(latencyMs = 800L)
void cache_survivesCaffeineEvictionDeadlock() { ...}
```

---

## Spring Framework (`macstab-chaos-testpacks-l3-spring`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-spring:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-spring</artifactId>`

### `@IncidentChaosSpringTransactionalPoolDeadlock` — ⚠️ CRITICAL

**Reproduces:** Spring `@Transactional(REQUIRES_NEW)` connection pool deadlock where N threads
each hold one connection and wait for a second — pool is exhausted — service freezes silently.
No database deadlock is visible in DB logs.

**Industry reference:** Spring Framework issue #26250 documents REQUIRES_NEW nested transaction
patterns that exhaust HikariCP pools, causing a full service hang with no observable
database-level deadlock signal.

**Composed of:**

- Connection: RECV latency at `latencyMs` ms, toxicity 1.0 — makes DB connections slow, accelerating pool exhaustion as
  threads accumulate in-flight connections
- JVM: `DataAccessResourceFailureException` injected at METHOD_EXIT on class prefix `classPattern` — reproduces the
  exception thrown when the connection pool queue is drained

| Attribute      | Type     | Default                             | Description                                                                           |
|----------------|----------|-------------------------------------|---------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                                | Container filter; empty = all containers                                              |
| `latencyMs`    | `long`   | `3000`                              | Milliseconds of RECV latency to apply to DB connections, accelerating pool exhaustion |
| `classPattern` | `String` | `"org.springframework.transaction"` | Class name prefix used to match Spring transaction methods for exception injection    |

```java

@Test
@IncidentChaosSpringTransactionalPoolDeadlock(latencyMs = 3000L)
void spring_survivesTransactionalPoolDeadlock() { ...}
```

---

### `@IncidentChaosSpringWebFluxReactorStarvation` — ⚠️ CRITICAL

**Reproduces:** Spring WebFlux reactor thread starvation where a blocking call on a reactor
carrier thread monopolises all 2×CPU reactor threads — the health endpoint times out — pod is
killed by the orchestrator.

**Industry reference:** JDriven post-mortem on blocking calls inside WebFlux reactive pipelines:
all reactor carrier threads (2×CPU) become blocked on a single synchronous dependency, starving
the event loop and causing liveness probe failure.

**Composed of:**

- Connection: RECV latency at `latencyMs` ms, toxicity 1.0 — every downstream I/O call blocks the reactor thread for the
  full latency duration, monopolising carriers
- JVM: `reactor.core.publisher.Operators$OnNextFailedException` injected at METHOD_EXIT on class prefix `classPattern` —
  reproduces the error propagated when a reactor operator drops an element due to a blocked pipeline

| Attribute      | Type     | Default          | Description                                                                       |
|----------------|----------|------------------|-----------------------------------------------------------------------------------|
| `id`           | `String` | `""`             | Container filter; empty = all containers                                          |
| `latencyMs`    | `long`   | `5000`           | Milliseconds of RECV latency to apply, blocking reactor threads on downstream I/O |
| `classPattern` | `String` | `"reactor.core"` | Class name prefix used to match reactor core methods for exception injection      |

```java

@Test
@IncidentChaosSpringWebFluxReactorStarvation(latencyMs = 5000L)
void spring_survivesWebFluxReactorStarvation() { ...}
```

---

### `@IncidentChaosSpringConfigRefreshWave` — 🔶 SEVERE

**Reproduces:** Spring Cloud Config simultaneous `/refresh` wave where 10+ nodes trigger bean
destruction concurrently — config server DNS lookup fails — refresh hangs — cascading timeout
across the fleet.

**Industry reference:** Spring Cloud Config issue #2341 documents the cascading failure when
multiple pods receive a simultaneous refresh trigger (e.g. from a config change push), causing a
synchronised bean destruction wave that drops traffic across the entire service fleet.

**Composed of:**

- Connection: RECV latency at `latencyMs` ms, toxicity 1.0 — config server responses are slow, causing refresh cycles to
  hang and accumulate
- DNS: EAI_AGAIN on all forward lookups — the config server hostname cannot be resolved during the refresh wave, making
  the hang indefinite
- JVM: `BeanCreationException` injected at METHOD_EXIT on class prefix `classPattern` — reproduces the failure raised
  when a bean cannot be created during the destructive refresh cycle

| Attribute      | Type     | Default                               | Description                                                                          |
|----------------|----------|---------------------------------------|--------------------------------------------------------------------------------------|
| `id`           | `String` | `""`                                  | Container filter; empty = all containers                                             |
| `latencyMs`    | `long`   | `1000`                                | Milliseconds of RECV latency to apply, making config server responses slow           |
| `classPattern` | `String` | `"org.springframework.cloud.context"` | Class name prefix used to match Spring Cloud context methods for exception injection |

```java

@Test
@IncidentChaosSpringConfigRefreshWave(latencyMs = 1000L)
void spring_survivesConfigRefreshWave() { ...}
```

---

### `@IncidentChaosSpringOsivConnectionStarvation` — 🔶 SEVERE

**Reproduces:** Spring Open Session In View (OSIV) connection starvation. With OSIV default ON,
a DB connection is held open for the entire HTTP request lifecycle including JSON serialisation.
At a traffic spike the connection pool is drained. Default Spring Boot configuration triggers
this — often mistaken for database slowness.

**Industry reference:** Spring Boot enables OSIV by default (`spring.jpa.open-in-view=true`), a
well-known production hazard documented in the Spring Data team's own migration guides. Teams
discover the problem only at scale, when connection pool exhaustion appears as generic 500 errors.

**Composed of:**

- Connection: RECV latency at `latencyMs` ms, toxicity 1.0 — extends the lifespan of each OSIV-held connection,
  accelerating pool drain under load

| Attribute   | Type     | Default | Description                                                               |
|-------------|----------|---------|---------------------------------------------------------------------------|
| `id`        | `String` | `""`    | Container filter; empty = all containers                                  |
| `latencyMs` | `long`   | `2000`  | Milliseconds of RECV latency to apply, extending OSIV connection lifespan |

```java

@Test
@IncidentChaosSpringOsivConnectionStarvation(latencyMs = 2000L)
void spring_survivesOsivConnectionStarvation() { ...}
```

---

## Feign / HTTP Clients (`macstab-chaos-testpacks-l3-feign`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-feign:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-feign</artifactId>`

### `@IncidentChaosFeignHystrixThreadLeak` — ⚠️ CRITICAL

**Reproduces:** Feign + Hystrix thread-leak failure where the Hystrix fallback fires on timeout,
but the underlying thread remains blocked in `socketRead0()` indefinitely. The thread pool drains
one thread per timed-out request; no recovery is possible without a pod restart.

**Industry reference:** Netflix/Hystrix issue #1240 — thread leak when Hystrix timeout fires but
the underlying socket read is still blocked in native code.

**Composed of:**

- Connection: RECV latency of `latencyMs` ms at 100% toxicity — makes every Feign call exceed the Hystrix timeout
  threshold
- JVM: HystrixTimeoutException injected at METHOD_EXIT on class prefix `classPattern` — triggers the fallback while the
  real thread remains stuck

| Attribute      | Type     | Default   | Description                                                                        |
|----------------|----------|-----------|------------------------------------------------------------------------------------|
| `id`           | `String` | `""`      | Container filter; empty = all containers                                           |
| `latencyMs`    | `long`   | `10000`   | RECV latency in milliseconds injected on all connections (Hystrix timeout trigger) |
| `classPattern` | `String` | `"feign"` | Class name prefix used to match Feign/Hystrix methods for exception injection      |

```java

@Test
@IncidentChaosFeignHystrixThreadLeak(latencyMs = 10000L)
void feign_survivesFeignHystrixThreadLeak() { ...}
```

---

### `@IncidentChaosFeignChunkedConnectionLeak` — 🔶 SEVERE

**Reproduces:** OpenFeign chunked-response connection-leak. A chunked HTTP response that never
completes is never explicitly closed by Apache HttpClient, so the connection is never returned to
the pool. The pool drains silently — no exception is thrown, no metric spikes — until the pod
eventually runs out of file descriptors and OOMs.

**Industry reference:** OpenFeign issue #1474 — chunked response not closed when using Apache
HttpClient; connection never returned to pool.

**Composed of:**

- Connection: RECV timeout — the chunked response hangs indefinitely, blocking the connection from ever being returned
  to the pool

| Attribute | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `id`      | `String` | `""`    | Container filter; empty = all containers |

```java

@Test
@IncidentChaosFeignChunkedConnectionLeak
void feign_survivesFeignChunkedConnectionLeak() { ...}
```

---

### `@IncidentChaosFeignRetryAmplification` — 🔶 SEVERE

**Reproduces:** Retry amplification from stacked retry policies. Feign's built-in retry (3
attempts) combined with a Resilience4j retry (3 attempts) multiplies every user request into up
to 9 upstream calls during a brownout, saturating downstream services rapidly.

**Industry reference:** Retry amplification through stacked policies is a well-known anti-pattern
in microservice resilience engineering; Feign × Resilience4j stacking is documented in multiple
post-mortems.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at toxicity `toxicity` — triggers the brownout condition that activates both retry
  layers
- JVM: IOException injected at METHOD_EXIT on class prefix `classPattern` — forces the Feign retry path to activate
  alongside Resilience4j retries

| Attribute      | Type     | Default   | Description                                                                    |
|----------------|----------|-----------|--------------------------------------------------------------------------------|
| `id`           | `String` | `""`      | Container filter; empty = all containers                                       |
| `toxicity`     | `double` | `0.5`     | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0)                |
| `classPattern` | `String` | `"feign"` | Class name prefix used to match Feign client methods for IOException injection |

```java

@Test
@IncidentChaosFeignRetryAmplification(toxicity = 0.5)
void feign_survivesFeignRetryAmplification() { ...}
```

---

### `@IncidentChaosOkHttpMetastablePool` — 🔶 SEVERE

**Reproduces:** OkHttp metastable pool failure where slow-server connections survive idle
connection cleanup because they are technically still active, causing the pool to bias toward
those slow connections — a self-sustaining feedback loop.

**Industry reference:** OkHttp issue #8244; HotOS 2021 paper on metastable failures in distributed
systems — pool bias toward slow connections is a canonical example of a metastable failure.

**Composed of:**

- Connection: RECV latency of `latencyMs` ms at 100% toxicity — makes connections slow but surviving, which the OkHttp
  pool interprets as valid idle connections

| Attribute   | Type     | Default | Description                                                                  |
|-------------|----------|---------|------------------------------------------------------------------------------|
| `id`        | `String` | `""`    | Container filter; empty = all containers                                     |
| `latencyMs` | `long`   | `2000`  | RECV latency in milliseconds injected on all connections (pool bias trigger) |

```java

@Test
@IncidentChaosOkHttpMetastablePool(latencyMs = 2000L)
void feign_survivesOkHttpMetastablePool() { ...}
```

---

### `@IncidentChaosFeignStaleLoadBalancer` — 🔷 MODERATE

**Reproduces:** Spring Cloud LoadBalancer stale-cache failure where the in-memory instance cache
has a default TTL of 35 seconds, meaning dead pod IPs from a rolling deploy remain in rotation
for up to 30 seconds. All requests to those IPs fail with ECONNREFUSED.

**Industry reference:** Spring Cloud LoadBalancer default cache TTL (`spring.cloud.loadbalancer.cache.ttl`)
is 35 seconds; stale cache during rolling deploys is a known operational pain point in Spring
Boot + Kubernetes deployments.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at toxicity `toxicity` — dead pod IPs served by the stale load balancer cache

| Attribute  | Type     | Default | Description                                                     |
|------------|----------|---------|-----------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                        |
| `toxicity` | `double` | `0.3`   | Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0) |

```java

@Test
@IncidentChaosFeignStaleLoadBalancer(toxicity = 0.3)
void feign_survivesFeignStaleLoadBalancer() { ...}
```

---

## System / OS (`macstab-chaos-testpacks-l3-system`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-system:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-l3-system</artifactId>`

### `@IncidentChaosSystemInodeExhaustion` — ⚠️ CRITICAL

**Reproduces:** Inode exhaustion where `df -h` shows 40% used and looks green, but every
`open()`, `mkdir()`, and temporary-file creation fails with `ENOSPC`. All inode slots are
consumed; operations staff stare at `df` output and see nothing wrong.

**Composed of:**

- Filesystem: OPEN → ENOSPC at `toxicity` — inode exhaustion manifests as ENOSPC on every file-open attempt

| Attribute  | Type     | Default | Description                                                |
|------------|----------|---------|------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                   |
| `toxicity` | `double` | `0.9`   | Fraction of `open()` calls that fail with ENOSPC (0.0–1.0) |

```java

@Test
@IncidentChaosSystemInodeExhaustion(toxicity = 0.9)
void system_survivesInodeExhaustion() { ...}
```

---

### `@IncidentChaosSystemFdExhaustion` — ⚠️ CRITICAL

**Reproduces:** File descriptor limit exhaustion where existing connections remain healthy, new
connections and file opens fail, but health checks pass because they reuse existing sockets.
The pod is never restarted by the orchestrator — only new operations fail.

**Composed of:**

- Filesystem: OPEN → EMFILE at `toxicity` — per-process fd limit hit on all new file opens
- Connection: CONNECT → ECONNREFUSED at `toxicity` — new socket creation fails because sockets also consume fd slots

| Attribute  | Type     | Default | Description                                                                                |
|------------|----------|---------|--------------------------------------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                                                   |
| `toxicity` | `double` | `0.8`   | Fraction of new file opens and socket connections that fail due to fd exhaustion (0.0–1.0) |

```java

@Test
@IncidentChaosSystemFdExhaustion(toxicity = 0.8)
void system_survivesFdExhaustion() { ...}
```

---

### `@IncidentChaosSystemSwapDeathSpiral` — ⚠️ CRITICAL

**Reproduces:** Swap death spiral where memory pressure causes the heap to be swapped out. A
GC cycle must traverse the swapped pages, triggering a 45-second stop-the-world pause. The
liveness probe times out and kills the pod; swap clears on restart; the cycle repeats
approximately every 3 minutes.

**Composed of:**

- JVM: HeapPressure retaining `heapFillMb` MB (pushes heap to swap)
- JVM: GcPressure at `allocationRateMbPerSec` MB/s (GC traverses swapped pages, causing 45-second STW pause)
- Filesystem: READ latency of 10 s on all paths (simulates swap page-fault latency on every file read)

| Attribute                | Type     | Default | Description                                                                       |
|--------------------------|----------|---------|-----------------------------------------------------------------------------------|
| `id`                     | `String` | `""`    | Container filter; empty = all containers                                          |
| `heapFillMb`             | `int`    | `256`   | Amount of heap to retain in megabytes, pushing the JVM toward swap                |
| `allocationRateMbPerSec` | `long`   | `100`   | GC allocation rate in megabytes per second, forcing GC traversal of swapped pages |

```java

@Test
@IncidentChaosSystemSwapDeathSpiral(heapFillMb = 512, allocationRateMbPerSec = 200L)
void system_survivesSwapDeathSpiral() { ...}
```

---

### `@IncidentChaosSystemTcpTimeWaitStorm` — 🔶 SEVERE

**Reproduces:** TCP TIME_WAIT storm caused by high connection churn where the ephemeral port
range is exhausted by sockets stuck in TIME_WAIT state. New outbound connections fail with
`EADDRNOTAVAIL`, manifesting to the application as `ECONNREFUSED`.

**Industry reference:** OkHttp issue #4354: high-throughput services with short-lived HTTP
connections exhaust the ephemeral port range when `SO_REUSEADDR`/`tcp_tw_reuse` are not
configured.

**Composed of:**

- Connection: CONNECT → ECONNREFUSED at `toxicity` — ephemeral port exhaustion manifests as connection failure on new
  outbound attempts

| Attribute  | Type     | Default | Description                                                                  |
|------------|----------|---------|------------------------------------------------------------------------------|
| `id`       | `String` | `""`    | Container filter; empty = all containers                                     |
| `toxicity` | `double` | `0.3`   | Fraction of outbound `connect()` calls that fail with ECONNREFUSED (0.0–1.0) |

```java

@Test
@IncidentChaosSystemTcpTimeWaitStorm(toxicity = 0.3)
void system_survivesTcpTimeWaitStorm() { ...}
```

---

### `@IncidentChaosSystemDirectMemoryLeak` — 🔶 SEVERE

**Reproduces:** Netty/gRPC off-heap ByteBuffer exhaustion where direct memory is allocated
without a `Cleaner` reference, preventing GC reclamation. The heap remains completely clean
and the pod is alive to liveness probes; every new NIO, Netty, or gRPC request fails.

**Industry reference:** Common pattern in services using Netty, gRPC-Java, or Kafka clients
where reference counting bugs or missing buffer releases cause gradual off-heap exhaustion
invisible to standard heap monitoring.

**Composed of:**

- JVM: DirectBufferPressure allocating `totalMb` MB in `bufferSizeMb` MB chunks, retained without a Cleaner (leak mode)
- JVM: `OutOfMemoryError("Direct buffer memory")` injection on `io.netty` classes at METHOD_EXIT

| Attribute      | Type     | Default | Description                                                   |
|----------------|----------|---------|---------------------------------------------------------------|
| `id`           | `String` | `""`    | Container filter; empty = all containers                      |
| `totalMb`      | `int`    | `256`   | Total direct memory to allocate and retain in megabytes       |
| `bufferSizeMb` | `int`    | `1`     | Size of each individual direct buffer allocation in megabytes |

```java

@Test
@IncidentChaosSystemDirectMemoryLeak(totalMb = 512, bufferSizeMb = 4)
void system_survivesSystemDirectMemoryLeak() { ...}
```

---

## Quick-reference index

| Annotation                                      | Module         | Severity    |
|-------------------------------------------------|----------------|-------------|
| `@IncidentChaosCaffeineEvictionDeadlock`        | l3-cache       | 🔶 SEVERE   |
| `@IncidentChaosCacheSerializationMismatch`      | l3-cache       | 🔶 SEVERE   |
| `@IncidentChaosCacheStampede`                   | l3-cache       | ⚠️ CRITICAL |
| `@IncidentChaosCacheWarmingFailure`             | l3-cache       | ⚠️ CRITICAL |
| `@IncidentChaosGrpcConnectionDrain`             | l3-grpc        | 🔶 SEVERE   |
| `@IncidentChaosGrpcDeadlinePropagation`         | l3-grpc        | ⚠️ CRITICAL |
| `@IncidentChaosGrpcGoawayStorm`                 | l3-grpc        | 🔶 SEVERE   |
| `@IncidentChaosGrpcLoadBalancingFailure`        | l3-grpc        | 🔶 SEVERE   |
| `@IncidentChaosHazelcastSplitBrain`             | l3-cache       | ⚠️ CRITICAL |
| `@IncidentChaosHttpCascadingTimeout`            | l3-http        | ⚠️ CRITICAL |
| `@IncidentChaosHttpPartialOutage`               | l3-http        | 🔶 SEVERE   |
| `@IncidentChaosHttpRetryAmplification`          | l3-http        | ⚠️ CRITICAL |
| `@IncidentChaosHttpSslHandshakeStorm`           | l3-http        | 🔶 SEVERE   |
| `@IncidentChaosJdbcConnectionStorm`             | l3-jdbc        | ⚠️ CRITICAL |
| `@IncidentChaosJdbcDiskFull`                    | l3-jdbc        | 🔶 SEVERE   |
| `@IncidentChaosJdbcNetworkPartition`            | l3-jdbc        | ⚠️ CRITICAL |
| `@IncidentChaosJdbcPrimaryFailover`             | l3-jdbc        | ⚠️ CRITICAL |
| `@IncidentChaosJdbcSequenceIdJump`              | l3-jdbc        | 🔶 SEVERE   |
| `@IncidentChaosJdbcWalPressure`                 | l3-jdbc        | 🔶 SEVERE   |
| `@IncidentChaosJvmCarrierPinning`               | l3-jvm         | ⚠️ CRITICAL |
| `@IncidentChaosJvmCodeCacheFull`                | l3-jvm         | ⚠️ CRITICAL |
| `@IncidentChaosJvmDeoptimizationStorm`          | l3-jvm         | 🔶 SEVERE   |
| `@IncidentChaosJvmDirectMemoryLeak`             | l3-jvm         | 🔶 SEVERE   |
| `@IncidentChaosJvmG1ToSpaceExhausted`           | l3-jvm         | ⚠️ CRITICAL |
| `@IncidentChaosJvmGcLockerFakeOom`              | l3-jvm         | 🔶 SEVERE   |
| `@IncidentChaosJvmMetaspaceGlacier`             | l3-jvm         | 🔶 SEVERE   |
| `@IncidentChaosJvmSafepointCascade`             | l3-jvm         | ⚠️ CRITICAL |
| `@IncidentChaosK8sCpuThrottleGcAmplification`   | l3-kubernetes  | ⚠️ CRITICAL |
| `@IncidentChaosK8sDnsNdots5Storm`               | l3-kubernetes  | 🔶 SEVERE   |
| `@IncidentChaosK8sOomKillMidGc`                 | l3-kubernetes  | ⚠️ CRITICAL |
| `@IncidentChaosK8sRollingUpdateRst`             | l3-kubernetes  | ⚠️ CRITICAL |
| `@IncidentChaosK8sSidecarShutdownRace`          | l3-kubernetes  | 🔶 SEVERE   |
| `@IncidentChaosKafkaBrokerFailure`              | l3-kafka       | ⚠️ CRITICAL |
| `@IncidentChaosKafkaClockDrift`                 | l3-kafka       | 🔷 MODERATE |
| `@IncidentChaosKafkaConsumerRebalance`          | l3-kafka       | 🔶 SEVERE   |
| `@IncidentChaosKafkaNetworkDegradation`         | l3-kafka       | 🔷 MODERATE |
| `@IncidentChaosKafkaStoragePressure`            | l3-kafka       | 🔶 SEVERE   |
| `@IncidentChaosKafkaUncleanLeaderElection`      | l3-kafka       | ⚠️ CRITICAL |
| `@IncidentChaosKafkaZookeeperLoss`              | l3-kafka       | ⚠️ CRITICAL |
| `@IncidentChaosFeignChunkedConnectionLeak`      | l3-feign       | 🔶 SEVERE   |
| `@IncidentChaosFeignHystrixThreadLeak`          | l3-feign       | ⚠️ CRITICAL |
| `@IncidentChaosFeignRetryAmplification`         | l3-feign       | 🔶 SEVERE   |
| `@IncidentChaosFeignStaleLoadBalancer`          | l3-feign       | 🔷 MODERATE |
| `@IncidentChaosOkHttpMetastablePool`            | l3-feign       | 🔶 SEVERE   |
| `@IncidentChaosRedisCacheAvalanche`             | l3-redis       | ⚠️ CRITICAL |
| `@IncidentChaosRedisClockDrift`                 | l3-redis       | 🔷 MODERATE |
| `@IncidentChaosRedisFailoverStorm`              | l3-redis       | ⚠️ CRITICAL |
| `@IncidentChaosRedisNetworkFlap`                | l3-redis       | ⚠️ CRITICAL |
| `@IncidentChaosRedisOomEviction`                | l3-redis       | 🔶 SEVERE   |
| `@IncidentChaosRedisSlowlog`                    | l3-redis       | 🔷 MODERATE |
| `@IncidentChaosSpringConfigRefreshWave`         | l3-spring      | 🔶 SEVERE   |
| `@IncidentChaosSpringConfigServerDown`          | l3-spring-boot | 🔶 SEVERE   |
| `@IncidentChaosSpringDatabaseOutage`            | l3-spring-boot | ⚠️ CRITICAL |
| `@IncidentChaosSpringGracefulShutdown`          | l3-spring-boot | 🔶 SEVERE   |
| `@IncidentChaosSpringMemoryCrisis`              | l3-spring-boot | ⚠️ CRITICAL |
| `@IncidentChaosSpringOsivConnectionStarvation`  | l3-spring      | 🔶 SEVERE   |
| `@IncidentChaosSpringStartupFailure`            | l3-spring-boot | ⚠️ CRITICAL |
| `@IncidentChaosSpringTransactionalPoolDeadlock` | l3-spring      | ⚠️ CRITICAL |
| `@IncidentChaosSpringWebFluxReactorStarvation`  | l3-spring      | ⚠️ CRITICAL |
| `@IncidentChaosSystemDirectMemoryLeak`          | l3-system      | 🔶 SEVERE   |
| `@IncidentChaosSystemFdExhaustion`              | l3-system      | ⚠️ CRITICAL |
| `@IncidentChaosSystemInodeExhaustion`           | l3-system      | ⚠️ CRITICAL |
| `@IncidentChaosSystemSwapDeathSpiral`           | l3-system      | ⚠️ CRITICAL |
| `@IncidentChaosSystemTcpTimeWaitStorm`          | l3-system      | 🔶 SEVERE   |
