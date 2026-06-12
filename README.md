<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

<div align="center">

# macstab-chaos-testing

**Container-level chaos engineering for any service, any image, any Linux distro. The production incidents that page your on-call become annotations that fail in PR review.**

[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Testcontainers](https://img.shields.io/badge/Testcontainers-powered-1DA1F2.svg)](https://testcontainers.com/)
[![glibc + musl](https://img.shields.io/badge/libc-glibc%20%2B%20musl-orange.svg)]()
[![amd64 + arm64](https://img.shields.io/badge/arch-amd64%20%2B%20arm64-lightgrey.svg)]()

*Designed and engineered by* **[Christian Schnapka](https://macstab.com)** —
Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

</div>

---

<div align="center">

### Part of the Macstab Chaos Engineering Stack

| [**JVM bytecode**](https://github.com/macstab/macstab-chaos-jvm-agent) | **Container orchestration** *(this repo)* | [**LD_PRELOAD libc**](https://github.com/macstab/macstab-chaos-testing-libraries) |
|:----------------------------------------------------------------------:|:-----------------------------------------:|:---------------------------------------------------------------------------------:|
| In-process chaos for JVM applications | Annotation-driven Testcontainers chaos for any container | Pure C99 syscall-level chaos for any Linux process |
| 62 JDK call sites · Spring 3/4 · Micronaut · Quarkus | ~448 L1 primitives · 92 L2 composites · 64 L3 incidents | glibc + musl × amd64 + arm64 · 100% line coverage |

**One mental model — three layers.** Start here for container-level chaos. Add the JVM layer for in-process fault injection. Add the libc layer for kernel-real syscall failures that no Java code can intercept.

</div>

---

## The Short Version

Your service has been on Kubernetes for two years. Every rolling deploy drops 0.3 % of in-flight requests with a TCP RST. Your Kubernetes platform team says "add retries." You add Feign retries (3×) and Resilience4j retries (3×). Now every user request during the 30-second drain window fires nine upstream calls. The downstream is in brownout. The retry storm makes the brownout worse.

You file a postmortem. You add the monitoring. You ship the fix. Three months later it breaks again, in a different service, because the root cause is *the architecture* — not the service.

**Add one annotation. Break it in PR review.**

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
@IncidentChaosK8sRollingUpdateRst      // 30 % of outbound RECV calls → ECONNRESET
@IncidentChaosFeignRetryAmplification  // ECONNREFUSED on 50 % of connect() → 3×3 upstream calls
class RollingDeployResilientTest {

    @Container @AppContainer
    static GenericContainer<?> app = new GenericContainer<>("my-app:latest")
        .withExposedPorts(8080);

    @Test
    void circuit_breaker_opens_before_retry_storm_amplifies() {
        final long upstreamCallsBefore = upstreamCallCount();
        sendUserRequests(100);
        // Without a circuit breaker: 100 user requests → 900 upstream calls.
        assertThat(upstreamCallCount() - upstreamCallsBefore).isLessThan(300);
    }
}
```

No sidecar. No mocks. No application code changes. A pre-compiled `.so` is copied into your container via the Docker API before it starts — no network access, no `apt-get`, no curl. The dynamic linker intercepts the syscalls. Your actual production image, your actual production code paths. **64 named production incidents auto-wired** across network, DNS, memory, I/O, process, time, JVM, Kubernetes, cache, Spring, Feign, Kafka, gRPC, JDBC, and Redis.

### What incidents does it let you reproduce?

The questions your on-call has to answer at 3 AM — turned into PR-blocking assertions:

- *"Does our circuit breaker open before Feign's 3× retry × Resilience4j's 3× retry = 9 upstream calls fires?"*
- *"Will HikariCP recover when 20 % of connections get ECONNRESET mid-transaction?"*
- *"Does our DNS client retry correctly when CoreDNS times out during a k8s ndots:5 lookup storm?"*
- *"Does our Redis client handle Sentinel failover when the master connection gets ECONNRESET?"*
- *"Does our Kafka consumer recover when the broker connection is dropped with ECONNREFUSED?"*
- *"What happens when mmap() starts failing with ENOMEM at 5 % probability — does the JVM crash or does our OOM handler fire?"*
- *"Does our application tolerate the fork() failures that happen when the k8s process limit is hit?"*

Every one of those becomes a test method that runs on every PR. No game day required. No SRE team required. No production blast radius. **Production failures become commits, not incidents.**

---

## Floor 0 — What it does (plain English)

You have a containerised service. You want to know what happens when:

- 20 % of outbound TCP connections get reset mid-read
- DNS resolution fails transiently on every fifth lookup
- The kernel refuses `mmap()` at 3 % of calls, simulating memory pressure
- `fork()` fails after N successful calls, hitting the process limit
- The clock drifts 200 ms forward, breaking your JWT expiry logic
- A rolling deploy triggers TCP RST on in-flight requests to the dying pod

This library lets you **turn those scenarios on and off with a single annotation**, scoped to a test class or a single test method. The chaos is injected at the libc layer — it applies to your actual application container, not a simulated proxy. Zero changes to your application code or your Docker image.

Three tiers:

| Tier | What you write | What you get |
|---|---|---|
| **L3** | `@IncidentChaosK8sDnsNdots5Storm` | Complete incident: ndots:5 → EAI_AGAIN on 20 % of lookups + response-time latency spike — the whole pattern, not just one fault |
| **L2** | `@CompositeChaosConnectionDrop` | Domain composite: ECONNRESET on 20 % of RECV calls, sensible defaults, named and documented |
| **L1** | `@ChaosRecvEconnreset(probability = 0.05)` | Raw primitive: exactly this errno, exactly this probability, no opinions |

Start at L3. Zoom in to L1 when you need surgical control.

---

## Floor -1 — Architecture (senior engineer territory)

Each `LibchaosLib` domain ships a pre-compiled C shared library (built from [`macstab-chaos-testing-libraries`](https://github.com/macstab/macstab-chaos-testing-libraries)) in four variants: `glibc-amd64`, `glibc-arm64`, `musl-amd64`, `musl-arm64`. The libraries are bundled in the module's own JAR.

`LibchaosTransport` is the deployment engine:

```
@SyscallLevelChaos(LibchaosLib.NET)
  └─► ChaosTestingExtension.beforeAll()
        └─► LibchaosTransport.prepare(container)         // before container.start()
              ├─► LibchaosVariant.resolve(container)     // image name → libc + arch
              ├─► ClassLoader.getResourceAsStream(...)   // .so from the module JAR
              ├─► container.copyFileToContainer(...)     // Docker API — no exec, no network
              └─► container.withEnv("LD_PRELOAD", ...)  // append to existing LD_PRELOAD
        └─► container.start()
              // dynamic linker loads the .so; syscall hooks are live before main()
```

Post-start, chaos rules are written to a config file inside the container (`/tmp/.chaos-net.conf`, `/tmp/.chaos-dns.conf`, etc.). The `.so` polls this file; changes take effect on the next matching syscall. `LibchaosTransport.addRule()` writes the rule, `removeRule()` deletes it.

The annotation pipeline:

```
@IncidentChaosK8sRollingUpdateRst
  └─► L3AnnotationProcessor.applyClassLevel()
        └─► L3Composer.compose(containers)              // multi-domain rule composition
              ├─► NetRule(RECV, ECONNRESET, 0.30)
              └─► DnsRule(EAI_AGAIN, 0.10)              // optional DNS component
        └─► LibchaosTransport.addRule(container, rule)  // one call per domain per container
```

**Session isolation**: class-scoped annotations apply at `@BeforeAll` and remove at `@AfterAll`. Method-scoped annotations apply at `@BeforeEach` and remove at `@AfterEach`. Handles are tracked per-container; `removeAll()` is idempotent.

---

## Floor -2 — LD_PRELOAD mechanics and the any-image story

The shared library is loaded by the dynamic linker (`ld.so` on glibc, `ld-musl.so` on Alpine) before any application code runs. It interposes on the libc wrappers for syscalls — `recv(2)`, `connect(2)`, `getaddrinfo(3)`, `mmap(2)`, `fork(2)`, etc. The application calls `recv()` through the PLT (Procedure Linkage Table); the linker resolves it to the chaos library's `recv()` wrapper, which consults the config file and either calls through to the real `recv()` or returns a configured `errno`.

**Why glibc and musl both work:** they use the same ELF dynamic linking protocol (`DT_NEEDED`, PLT, GOT). The symbol names differ slightly — musl exports `recv` directly while glibc exports `__recv` + alias — so separate `.so` files are compiled for each libc. The `LibchaosVariant` resolver selects the correct one from the image name: `alpine` → musl, everything else → glibc.

**Why it works on any image:** the `.so` is copied into the container via `container.copyFileToContainer()` — the Docker API's file-transfer endpoint, which uses a tar stream over the Docker socket. No `wget`. No `curl`. No package manager. No network access inside the container. No shell. The container does not need `bash`, `sh`, or any userland tools for the copy to succeed. It works on `FROM distroless`, `FROM scratch`, `FROM redhat/ubi-micro`, and every Alpine and Debian derivative.

```
Your test JVM                       Container filesystem
─────────────────                   ───────────────────
module JAR                          /usr/local/lib/
  └─ libchaos-net-musl-amd64.so ──► libchaos-net.so   ← LD_PRELOAD
                    (Docker API tar stream, no exec)
```

The only requirement: the container process is a Linux ELF binary that uses the standard dynamic linker. Static binaries linked with `-static-pie` and `musl-libc --static` do not go through the PLT and are unaffected.

---

## Quick Start

**1. Add the dependency**

```groovy
// build.gradle — pick the testpacks for the incidents you care about
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kubernetes:1.0.0'
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-jvm:1.0.0'
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-spring:1.0.0'
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kafka:1.0.0'
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-cache:1.0.0'
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-feign:1.0.0'
// … or pull everything:
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-all:1.0.0'
```

**2. Write the test**

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)                 // prepare the network chaos library
class MyServiceChaosTest {

    @Container @AppContainer                         // @AppContainer marks the target
    static GenericContainer<?> app =
        new GenericContainer<>("my-service:latest").withExposedPorts(8080);

    @Test
    @IncidentChaosK8sRollingUpdateRst               // method-scope: active for this test only
    void service_handles_rst_during_rolling_deploy() {
        assertThat(callService("/api/order")).isEqualTo(200);
    }
}
```

**3. Run**

```bash
./gradlew test
```

No YAML. No sidecar. No CI pipeline changes. The chaos is live on the next `./gradlew test`.

---

## Core Concepts

| Concept | What it is |
|---|---|
| **L3 annotation** | One annotation = one named production incident. Composes multiple domains, timing, and optional JVM stressors from `macstab-chaos-jvm-agent`. |
| **L2 annotation** | One annotation = one named single-domain fault composite. Sane defaults; documented severity and probability. |
| **L1 annotation** | One annotation = one syscall + one errno + one probability. Maximum control, maximum verbosity. |
| **`@SyscallLevelChaos`** | Marks the test class and lists which `LibchaosLib` domains to prepare. The extension installs the `.so` into each `@AppContainer` before it starts. |
| **`@AppContainer`** | Marks which containers in the test class are the chaos targets. Multiple containers supported; each gets its own transport. |
| **`LibchaosTransport`** | The deployment engine. Pre-start: copies `.so`, sets `LD_PRELOAD`. Post-start: writes/deletes config file rules. |
| **`RuleHandle`** | Returned by every `apply()` call. `remove(handle)` deletes that rule. `removeAll(container)` clears the whole config. |
| **`ChaosPattern`** | Temporal value generator. Ramp, step, repeat, compose. Drives rule probability over time for load-test scenarios. |

---

## L3 — Named Production Incidents

64 annotations. Each encodes a complete production incident — multi-domain, with timing and severity calibrated to real post-mortems. Pick the annotation that matches the incident in your runbook.

### JVM

| Annotation | What it simulates | Why it's unique |
|---|---|---|
| `@IncidentChaosJvmCarrierPinning` | All ForkJoinPool carriers pinned by `synchronized` → virtual thread starvation | Only reproducible with JVM-internal access; zero exceptions in logs |
| `@IncidentChaosJvmCodeCacheExhaustion` | JIT code cache fills → interpreter fallback → 10–50× throughput drop | Accumulates over days; no OOM, no error, just slowdown |
| `@IncidentChaosJvmG1ToSpaceExhausted` | G1 heap exceeds Xmx mid-GC → RSS hits cgroup limit → exit 137 | Heap metrics show 60 %; JVM never threw `OutOfMemoryError` |
| `@IncidentChaosJvmSafepointCascade` | STW pause + connection ECONNRESET + DNS EAI_AGAIN simultaneously | HikariCP + ZK + Kafka all fire at once; looks like a traffic spike |
| `@IncidentChaosJvmGcLockerFakeOom` | GC blocked by monitor contention → spurious OOM despite heap room | Restart "fixes" it; root cause is never found |
| `@IncidentChaosJvmMetaspaceGlacier` | Classloader leak at configurable MB/hour | Heap metrics green for hours; then Metaspace OOM |
| `@IncidentChaosJvmDirectMemoryLeak` | Netty/gRPC off-heap exhaustion; heap clean; pod zombie-functional | Every new request fails; probes pass; no log entries |

### Kubernetes

| Annotation | What it simulates |
|---|---|
| `@IncidentChaosK8sRollingUpdateRst` | iptables endpoint removal lag → ECONNRESET on in-flight requests during every rolling deploy |
| `@IncidentChaosK8sDnsNdots5Storm` | ndots:5 → 4 NXDOMAIN queries per external lookup → CoreDNS overload → 5–20 s DNS timeouts |
| `@IncidentChaosK8sOomKillMidGc` | G1 RSS spike → cgroup OOM kill (exit 137) mid-GC — heap at 60 %, no Java OOM in logs |
| `@IncidentChaosK8sSidecarShutdownRace` | SIGTERM to all containers simultaneously → Envoy closed before app drains → ECONNREFUSED |
| `@IncidentChaosK8sCpuThrottleGcAmplification` | CPU limit → GC threads throttled → 50 ms GC becomes 400 ms → liveness probe kills pod |

### Cache

| Annotation | What it simulates |
|---|---|
| `@IncidentChaosCacheStampede` | Hot key expires → 100–1000× concurrent DB queries → DB locks → cache can't refill → death spiral |
| `@IncidentChaosCacheWarmingFailure` | Cold start → backend sized for cached load → overwhelmed → cache stays cold |
| `@IncidentChaosCacheSerializationMismatch` | Rolling deploy → old Redis entries fail deserialization on new pods → 100 % cache miss |
| `@IncidentChaosCaffeineEvictionDeadlock` | Single slow Caffeine loader holds evictionLock → 1,400 threads waiting on unrelated keys |
| `@IncidentChaosHazelcastSplitBrain` | Network partition → split writes → silent data loss on healing |

### Spring

| Annotation | What it simulates |
|---|---|
| `@IncidentChaosSpringTransactionalPoolDeadlock` | `@Transactional(REQUIRES_NEW)` + slow DB → thread holds connection, waits for second → pool exhausted |
| `@IncidentChaosSpringWebFluxReactorStarvation` | Blocking call on reactor thread → all 2×CPU threads monopolized → health endpoint times out |
| `@IncidentChaosSpringOsivConnectionStarvation` | OSIV default ON → DB connection held during JSON serialization → pool exhausted at traffic spike |
| `@IncidentChaosSpringConfigRefreshWave` | Simultaneous `/refresh` across 10+ nodes → bean destruction wave → cascading timeout |

### Feign / HTTP clients

| Annotation | What it simulates |
|---|---|
| `@IncidentChaosFeignHystrixThreadLeak` | Feign + Hystrix timeout: fallback fires but thread stuck in `socketRead0()` forever |
| `@IncidentChaosFeignRetryAmplification` | Feign retry (3×) × Resilience4j retry (3×) = 9 upstream calls per request during brownout |
| `@IncidentChaosFeignStaleLoadBalancer` | Spring Cloud LoadBalancer 30 s stale cache → dead pod IPs served 30 s after rolling deploy |
| `@IncidentChaosOkHttpMetastablePool` | Pool bias toward slow servers → all requests route to slowest pod → it slows more |

### Kafka / gRPC / JDBC / Redis

| Annotation | What it simulates |
|---|---|
| `@IncidentChaosKafkaLeaderElection` | Broker failover → producer blocks indefinitely; consumer group rebalance storm |
| `@IncidentChaosKafkaZookeeperLoss` | ZK metadata unavailable → producer blocks → topic creation fails silently |
| `@IncidentChaosGrpcGoawayStorm` | `maxConnectionAge` GOAWAY + high concurrency → steady UNAVAILABLE errors |
| `@IncidentChaosJdbcConnectionPoolExhaustion` | `@Transactional(REQUIRES_NEW)` deadlock: no DB deadlock visible, just hung threads |
| `@IncidentChaosJdbcSequenceIdJump` | Postgres sequence pre-allocates 32 WAL values; IDs jump after failover |
| `@IncidentChaosRedisNetworkFlap` | Rapid ECONNRESET cycling → Sentinel election storm; clients see master change every 200 ms |

[→ Full L3 reference with attribute tables and severity ratings](docs/l3-reference.md)

---

## L2 — Named Composite Scenarios

92 annotations across 7 domains. One domain, one named failure mode, sensible defaults. Use L2 when L3 is too opinionated and L1 is too verbose.

```java
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosConnectionDrop           // ECONNRESET on 20 % of RECV — "the connection drops under load"
@CompositeChaosConnectionTimeout        // EAGAIN on 10 % of RECV — "the connection hangs"
class ConnectionResilienceTest { ... }
```

```java
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosTransientDnsFailure      // EAI_AGAIN on 15 % of getaddrinfo — "DNS is flaky"
class DnsResilienceTest { ... }
```

```java
@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosLowMemoryPressure        // ENOMEM on 1 % of mmap — "the pod is under memory pressure"
class OomResilienceTest { ... }
```

[→ Full L2 reference](docs/l2-reference.md)

---

## L1 — Raw Syscall Primitives

~448 annotations. One syscall, one errno, one probability. Use L1 when you need exact fault control.

```java
// inject ECONNRESET on exactly 5 % of recv() calls
@ChaosRecvEconnreset(probability = 0.05)

// inject ENOMEM on mmap() at 0.1 % — simulates chronic low-level memory pressure
@ChaosMmapEnomem(probability = 0.001)

// make getaddrinfo() fail with EAI_AGAIN for hostnames matching "*.internal"
@ChaosDnsGetaddrinfoEaiAgain(hostPattern = "*.internal", probability = 0.2)

// fail fork() after exactly 64 successful calls
@ChaosProcessForkFailAfter(n = 64)

// inject ETIMEDOUT on connect() at 3 %
@ChaosConnectEtimedout(probability = 0.03)
```

All L1 annotations support:
- **`probability`** — fraction of matching calls that fail (default `1.0`)
- **`id`** — container filter; empty string targets all `@AppContainer` containers
- **`onMissingEnv`** — `ERROR` (default, fails the test) or `ABORT` (skips the test as yellow in CI)
- **`@Repeatable`** — multiple L1s stack on the same class or method

[→ Full L1 reference with all ~448 annotations grouped by module and syscall family](docs/l1-reference.md)

---

## Patterns — Temporal Fault Injection

`ChaosPattern` generates values over time and applies them to any chaos operation. Use it to drive fault probability through a ramp, a step function, or a repeating wave — for load tests, soak tests, and SLO validation under time-varying pressure.

```java
// Ramp fault probability from 0 % to 30 % over 60 seconds.
// Finds the threshold where the circuit breaker should open.
final ChaosPattern<Double> ramp = RampPattern.linear(0.0, 0.30);

ramp.applyTo(
    p -> chaos.apply(container, NetRule.recv(ECONNRESET).probability(p)),
    Duration.ofSeconds(60),
    Duration.ofMillis(500))
  .awaitUninterruptibly();
```

```java
// Repeat a sawtooth 3 times — apply and remove a fault window in each cycle.
final ChaosPattern<Double> sawtooth = RampPattern.linear(0.0, 1.0)
    .then(RampPattern.linear(1.0, 0.0), Duration.ofSeconds(10))
    .repeat(3);
```

`PatternExecution` is returned immediately. Call `.awaitUninterruptibly()` to block until the pattern completes, or `.stop()` to cancel early. The completion sentinel fires one sample interval after `totalDuration` to guarantee the last sample's `remove()` call completes before unblocking.

---

## Recipes

Three fully-worked examples. Each is self-contained — copy, adapt the container name, and run.

### Recipe 1 — Circuit breaker opens before retry storm

**Goal.** Prove that a Resilience4j `CircuitBreaker` transitions to OPEN before a Feign (3×) × Resilience4j (3×) retry composition multiplies a brownout into a storm.

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
class RetryAmplificationTest {

    @Container @AppContainer
    static GenericContainer<?> downstream =
        new GenericContainer<>("downstream-service:latest").withExposedPorts(8080);

    @Test
    @IncidentChaosFeignRetryAmplification   // ECONNREFUSED on 50 % of connect()
    void circuit_breaker_engages_before_9x_amplification() {
        final long callsBefore = callCounter.get();

        sendUserRequests(100);

        // Without a circuit breaker: 100 user requests → 900 downstream calls.
        // With a circuit breaker: it opens after ~20 failures, fast-rejecting the rest.
        assertThat(callCounter.get() - callsBefore)
            .as("retry amplification must be suppressed by circuit breaker")
            .isLessThan(300);

        assertThat(circuitBreaker.getState())
            .isEqualTo(CircuitBreaker.State.OPEN);
    }
}
```

**What this exercises.** `@IncidentChaosFeignRetryAmplification` applies `ChaosConnectEconnrefused` at 50 % toxicity to the container's outbound `connect()` calls. Feign retries each failed call 3 times; Resilience4j retries the whole Feign call 3 times. The 9× amplification is the production failure mode. The assertion proves the circuit breaker breaks the loop.

---

### Recipe 2 — DNS resilience during k8s ndots:5 storm

**Goal.** Verify that your service's DNS client retries correctly when CoreDNS is overwhelmed by the ndots:5 search-domain exhaustion pattern, and that you do not see cascading 20-second timeouts.

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.DNS)
class DnsNdots5ResilienceTest {

    @Container @AppContainer
    static GenericContainer<?> app =
        new GenericContainer<>("my-app:latest").withExposedPorts(8080);

    @Test
    @IncidentChaosK8sDnsNdots5Storm   // EAI_AGAIN on 20 % of getaddrinfo + 100ms latency
    void dns_timeouts_do_not_cascade_under_ndots5_storm() {
        final long startMs = System.currentTimeMillis();

        // Call any endpoint that does an external DNS lookup
        final var response = callServiceEndpoint("/api/external-data");

        assertThat(response.statusCode()).isEqualTo(200);
        // ndots:5 exhaustion causes 20 s timeouts if the client doesn't retry aggressively.
        assertThat(System.currentTimeMillis() - startMs).isLessThan(5_000);
    }
}
```

**What this exercises.** The incident annotation injects EAI_AGAIN at 20 % of `getaddrinfo()` calls plus 100 ms of synthetic latency on successful lookups. With ndots:5, every external hostname triggers four search-domain queries before the absolute lookup. Under this annotation, roughly 60 % of those four queries fail — reproducing the CoreDNS overload pattern. The assertion fails if your DNS client has a 5-second default timeout without retry, which is the common misconfiguration.

---

### Recipe 3 — Ramp to find your OOM recovery threshold

**Goal.** Use a probability ramp to find the exact `mmap()` failure rate at which your application's OOM handler fires versus silently corrupting state.

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.MEMORY)
class OomThresholdTest {

    @Container @AppContainer
    static GenericContainer<?> app =
        new GenericContainer<>("my-app:latest").withExposedPorts(8080);

    private LibchaosMemoryChaos memoryChaos;

    @Test
    void oom_handler_fires_before_data_corruption() throws Exception {
        memoryChaos = new LibchaosMemoryChaos();

        // Ramp mmap() ENOMEM probability 0 % → 10 % over 30 seconds.
        // At the threshold, the OOM handler should fire and return 503 — not 500 or 200 with corrupt data.
        final ChaosPattern<Double> ramp = RampPattern.linear(0.0, 0.10);

        ramp.applyTo(
                p -> memoryChaos.simulateMemoryPressure(app, p),
                Duration.ofSeconds(30),
                Duration.ofMillis(500))
            .awaitUninterruptibly();

        // The application must never have returned 500 (corrupt) during the ramp.
        assertThat(errorMetrics.http500Count()).isZero();
        // The OOM handler must have fired at least once — proving the safety net works.
        assertThat(errorMetrics.http503Count()).isPositive();
    }
}
```

**What this exercises.** The ramp finds the failure rate where your application transitions from "healthy" to "degraded but safe" (503) vs "corrupted" (500 or 200 with wrong data). The assertion shape is the key: zero 500s means the OOM handler intercepted every memory allocation failure before it produced corrupt output. This test catches the class of bugs where `malloc()` failure propagates silently through a null pointer instead of being handled.

---

## Platform Support

| Platform | Syscall chaos (L1/L2/L3) | tc/iptables network chaos | Notes |
|---|---|---|---|
| Linux (native) | ✅ glibc + musl | ✅ | Full support |
| GitHub Actions (`ubuntu-latest`) | ✅ | ✅ | Full support |
| GitLab CI (Linux runner) | ✅ | ✅ | Full support |
| Docker Desktop (macOS) | ✅ | ✅ | Linux VM handles syscalls correctly |
| Docker Desktop (Windows) | ✅ | ✅ | Linux VM handles syscalls correctly |
| Dev container (any OS) | ✅ | ✅ | Full support |
| Alpine-based images | ✅ musl variant | ✅ | `libchaos-*-musl-*.so` selected automatically |
| ARM64 (Apple M-series, Graviton) | ✅ arm64 variant | ✅ | `libchaos-*-*-arm64.so` selected by `os.arch` |

**Static binaries** (linked with `-static-pie` or `musl --static`) do not use the PLT and are not affected by LD_PRELOAD. This is a hard constraint of how ELF dynamic linking works, not a limitation of this library.

---

## Build

```bash
./gradlew build                    # compile + test all modules
./gradlew test                     # integration tests (requires Docker)
./gradlew :macstab-chaos-connection:test
./gradlew :macstab-chaos-process:test
./gradlew :macstab-chaos-patterns:test
```

Requires Java 21+, Docker 20.10+. Integration tests start real containers — they require a running Docker daemon. Linux host or Docker Desktop for full syscall coverage.

---

## Documentation

| Document | What it covers |
|---|---|
| [Getting Started](docs/getting-started.md) | Gradle/Maven coordinates, prerequisites, first test walkthrough |
| [Scoping Guide](docs/scoping-guide.md) | `@AppContainer`, `id()` routing, class vs method scope, `@SyscallLevelChaos` |
| [L3 Reference](docs/l3-reference.md) | All 64 named incidents with attribute tables, severity ratings, and postmortem links |
| [L2 Reference](docs/l2-reference.md) | All 92 composite scenarios across 7 domains |
| [L1 Reference](docs/l1-reference.md) | All ~448 raw syscall primitives grouped by module and syscall family |
| [Redis Guide](docs/redis-guide.md) | `@RedisStandalone`, `@RedisSentinel`, chaos combinations |
| [Package Manager Reference](docs/PACKAGE_MANAGER_TECHNICAL_REFERENCE.md) | Universal distro detection, all 6 package managers, APT/APK/DNF/YUM/Pacman/Zypper |
| [Network Chaos Reference](docs/NETWORK_CHAOS_TECHNICAL_REFERENCE.md) | tc/netem internals, qdisc algorithms, Gilbert-Elliott loss model |

---

## License

Apache License 2.0 — see [LICENSE](LICENSE). Use it in production, ship it in your products, fork it, build a business around it. The only thing you cannot do is claim you wrote it.

---

## About the Engineer

This three-repo stack — [`macstab-chaos-jvm-agent`](https://github.com/macstab/macstab-chaos-jvm-agent), `chaos-testing` (this repo), [`macstab-chaos-testing-libraries`](https://github.com/macstab/macstab-chaos-testing-libraries) — is the work of one engineer: **Christian Schnapka**, Hamburg, Germany.

42 years of programming. 6502 on the C64 at 10. Motorola 68000 / Amiga demoscene from 15 — **Razor 1911**, **Sanity**, **Anthrox**, **Incal**. Shipping for game studios (**Software 2000**, **Rainbow Arts**, studios in Birmingham) at 16, on cartridges with no patch button. Java since 1.0. Linux containers since LXC in 2008. Docker since first release. 30 years of production enterprise software.

**Diplom Informatiker** — German pre-Bologna 5-year computer science degree, equivalent to a master's.

The breadth in this library — pre-compiled C99 .so binaries for four libc/arch combinations bundled in a Java JAR, Docker API tar-stream injection that works on distroless images, an L3 incident layer that composes syscall faults with JVM agent stressors across multiple containers, a temporal pattern engine that ramps fault probability over time — comes from a path that started with peeking C64 memory at 10, ran through the demoscene where every byte and every cycle counted, through game studios where you shipped once and it had to work, and then 30 years of production systems. Most engineers enter at the framework layer and look down. **This stack reads from below.**

### Available for senior engineering engagements

Limited capacity. Typically:

- **Fractional / interim Principal Engineer** — architecture, mentoring, hardest-problem ownership
- **Reliability engineering** — chaos-engineering / SRE-tooling enablement, post-incident systemic fixes
- **JVM performance** — agents, GC tuning, instrumentation, deep profiling
- **Container and systems-level work** — C / C++, LD_PRELOAD internals, Linux kernel interfaces

If your team is fighting production incidents that "more tests" hasn't fixed:

- **[macstab.com](https://macstab.com)** — engagement enquiries
- **info@macstab.com** — direct contact
- **[GitHub @macstab](https://github.com/macstab)** — more open-source work

A small number of engagements per year. The work is deep — production systems with receipts in `git log`, not slide decks.

---

<div align="center">

**[Christian Schnapka](https://macstab.com)**
Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
