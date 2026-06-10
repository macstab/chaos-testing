# Macstab Chaos Testing Framework

**The only Java chaos testing framework where a single annotation reproduces a named production incident — in your JUnit 5 test, on any CI runner, with zero infrastructure.**

[![Maven Central](https://img.shields.io/maven-central/v/com.macstab.chaos/macstab-chaos-core)](https://search.maven.org/search?q=g:com.macstab.chaos)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Docker](https://img.shields.io/badge/Docker-20.10+-blue.svg)](https://www.docker.com/)

---

**Created by:** [Christian Schnapka](https://macstab.com), Principal+ Engineer @ [Macstab GmbH](https://macstab.com)  
**License:** [Apache 2.0](LICENSE)

---

## The annotation that reproduces your worst outage

Every engineering team has *the incident* — the one that woke everyone up at 3am, the one that took four engineers two days to root-cause, the one that the post-mortem said "add monitoring for this." Six months later it happens again.

This framework makes those incidents reproducible in a unit test, on every PR, before they reach production.

```java
// This annotation reproduces the Twitter / Reddit / Instagram cache stampede —
// hot key expires → 1,000 concurrent DB queries → DB locks → cache can't refill → death spiral
@IncidentChaosCacheStampede
@Test
void cache_stampede_does_not_cascade_to_database(ApplicationUnderTest app) {
    app.evictCacheKey("popular-item");
    sendConcurrentRequests(app, 1000);
    assertThat(app.databaseQueryCount()).isLessThan(50);  // Circuit breaker must engage
}
```

Behind that annotation: the framework simultaneously injects connection latency on your database container (simulating the DB struggling under load) and forces your cache client's `get()` to return null with configurable probability (simulating the cache miss storm). The combination creates the exact failure mode — not a synthetic approximation.

---

## Three tiers, one framework

Pick the level of abstraction that fits the job:

| Tier | Abstraction | Count | Use when |
|---|---|---|---|
| **L3** | Named production incident | 64 annotations | Reproduce a real outage pattern end-to-end |
| **L2** | Named composite scenario | 92 annotations | One domain (network drop, timeout, reset) with sane defaults |
| **L1** | Raw syscall primitive | ~448 annotations | Exact fault control — specific errno, exact probability |

---

## L3 — Named production incidents

L3 is where this framework is unique. Each annotation encodes a complete incident: the combination of faults, their timing, and the system layers they hit, distilled from real post-mortems.

### Example: JDK 21 virtual thread starvation

In JDK 21+, a `synchronized` block on a carrier thread pins the virtual thread to that carrier. If all carriers are pinned, your entire thread pool freezes — zero exceptions, zero thread pool warnings, zero stack traces. The health endpoint times out. The liveness probe kills the pod. No one knows why.

```java
@AppContainer
@JvmAgentChaos
@IncidentChaosJvmCarrierPinning   // pins all carrier threads with synchronized monitors
class VirtualThreadStarvationTest {

    @Test
    void service_does_not_deadlock_when_carriers_are_pinned(ConnectionInfo conn) {
        // Under this annotation:
        // - daemon threads hold synchronized monitors, saturating the carrier pool
        // - virtual threads that need a carrier block indefinitely
        // - your service appears alive to the health endpoint but processes no requests

        final var response = conn.get("/api/data", Duration.ofSeconds(5));
        assertThat(response.statusCode()).isEqualTo(200);  // must not hang
    }
}
```

This test fails if your application hasn't been updated to avoid `synchronized` on hot paths in JDK 21. No other tool can reproduce this — it requires JVM internals access that network-layer chaos tools simply don't have.

### Example: Kubernetes rolling deploy TCP RST storm

During a Kubernetes rolling deploy, iptables endpoint removal lags behind the pod going away. In-flight requests to the dying pod receive a TCP RST. This happens on **every rolling deploy**, accounts for 73% of Slack's deploy-time incidents, and is invisible in logs because the RST is below the HTTP layer.

```java
@AppContainer
@SyscallLevelChaos(LibchaosLib.NET)
@IncidentChaosK8sRollingUpdateRst   // 30% of RECV calls on the app's outbound connections → ECONNRESET
class RollingDeployResilientTest {

    @Test
    void retry_logic_handles_rst_during_deploy(ApplicationUnderTest app) {
        assertThat(app.callDownstreamService()).isEqualTo(200);
    }
}
```

### More L3 incidents

```java
@IncidentChaosJdbcConnectionPoolExhaustion   // Spring @Transactional(REQUIRES_NEW) deadlock — no DB deadlock visible
@IncidentChaosK8sDnsNdots5Storm              // ndots:5 → 4 NXDOMAIN per lookup → 20s DNS timeouts
@IncidentChaosKafkaLeaderElection            // broker failover → producer blocks indefinitely  
@IncidentChaosSpringWebFluxReactorStarvation // blocking call on reactor thread → health endpoint killed
@IncidentChaosHazelcastSplitBrain           // network partition → split writes → silent data loss
@IncidentChaosJvmCodeCacheExhaustion        // JIT disabled → 10–50× slowdown, zero exceptions, accumulates over days
@IncidentChaosGrpcGoawayStorm               // maxConnectionAge GOAWAY + concurrency → steady UNAVAILABLE errors
```

64 incidents in total, covering JVM, Kubernetes, cache, Spring, Feign, Kafka, gRPC, JDBC, Redis, and system-level faults. [See the full L3 reference →](docs/l3-reference.md)

---

## L2 — Named composite scenarios

L2 gives you a named, documented failure mode for a single domain, with sane defaults. No need to know which errno to inject or what probability to set.

```java
// L2 — "the database connection drops under load"
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosConnectionDrop          // connection-level ECONNRESET on 20% of RECV calls
class ConnectionDropTest {

    @Test
    void connection_pool_recovers_after_drop(DataSource ds) {
        // assert your pool reconnects and queries succeed
    }
}
```

```java
// L2 — "DNS resolution is flaky"
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosTransientDnsFailure     // EAI_AGAIN on 15% of getaddrinfo calls
class DnsResilienceTest { ... }
```

92 L2 composites across 7 domains: network, DNS, memory, I/O, process, time, and JVM. [See the L2 reference →](docs/l2-reference.md)

---

## L1 — Raw syscall primitives

L1 gives you direct control over a single syscall at a configurable probability. Use it when L2 and L3 are too coarse.

```java
// L1 — inject ENOMEM into mmap() at exactly 0.1% of calls
@SyscallLevelChaos(LibchaosLib.MEMORY)
@ChaosMmapEnomem(probability = 0.001)
class LowProbabilityOomTest { ... }
```

```java
// L1 — inject ECONNRESET on recv() at 5% of calls
@SyscallLevelChaos(LibchaosLib.NET)
@ChaosRecvEconnreset(probability = 0.05)
class NetworkFlakynessTest { ... }
```

```java
// L1 — force fork() to fail after exactly N successful calls
@SyscallLevelChaos(LibchaosLib.PROCESS)
@ChaosProcessForkFailAfter(n = 64)
class ProcessLimitTest { ... }
```

~448 L1 primitives across memory, network, DNS, I/O, process, time, and JVM interceptors. Every meaningful syscall, every meaningful errno. [See the L1 reference →](docs/l1-reference.md)

---

## Works on any Docker image — zero dependencies inside the container

Most chaos tools require either network access inside the container (to download agents), a specific base image, or a sidecar container. This framework requires none of those.

**How it works:**

The framework ships four pre-compiled `.so` variants in its own JAR:

| Variant | libc | Architecture |
|---|---|---|
| `libchaos-net-glibc-amd64.so` | glibc (Debian, Ubuntu, RHEL, …) | x86_64 |
| `libchaos-net-glibc-arm64.so` | glibc | aarch64 |
| `libchaos-net-musl-amd64.so` | musl (Alpine) | x86_64 |
| `libchaos-net-musl-arm64.so` | musl | aarch64 |

Before the container starts, `LibchaosTransport`:

1. Inspects the image name to select the right variant (`alpine` → musl, anything else → glibc)
2. Copies the `.so` into the container via the Docker API — no network, no `apt-get`, no `curl`
3. Sets `LD_PRELOAD` in the container environment

The container starts with the chaos library already intercepting syscalls at the dynamic linker level. Your application code is unmodified. No JVM agent. No sidecar. No kernel module.

```java
// This works on all of these — without any changes to the image:
// FROM amazoncorretto:21-alpine
// FROM eclipse-temurin:21-bookworm
// FROM redhat/ubi9-minimal:latest
// FROM gcr.io/distroless/java21
// FROM scratch (with a statically-linked binary)

@SyscallLevelChaos(LibchaosLib.NET)
@ChaosRecvEconnreset(probability = 0.1)
class AnyImageTest {
    @Container @AppContainer
    static GenericContainer<?> app = new GenericContainer<>("your-actual-prod-image:latest")
        .withExposedPorts(8080);

    @Test
    void test_with_your_real_image() { ... }
}
```

The only requirement: the container runs Linux. glibc and musl are both covered. amd64 and arm64 are both covered. No "supported base images" list. No footnote saying "Alpine not supported."

---

## Network chaos on any image — universal package installer

For scenarios that need `tc` (traffic control) or `iptables` — network latency, packet loss, bandwidth limiting — the framework includes a universal package installer that auto-detects the container's Linux distribution and installs the right packages:

```java
// Works on Debian, Ubuntu, Alpine, Fedora, RHEL/CentOS, Arch, openSUSE —
// without you specifying which package manager to use.
PackageManager.detect(container).install(container, "iproute2", "iptables");
```

Supported package managers: APT (Debian/Ubuntu), APK (Alpine), DNF (Fedora/RHEL 8+), YUM (CentOS/RHEL 7), Pacman (Arch), Zypper (openSUSE).

For the annotation path, `@SyscallLevelChaos` with `LibchaosLib.NET` handles this automatically — the extension installs tools if needed before the first chaos rule is applied.

---

## Why this is different

| | Chaos Mesh | Toxiproxy | Gremlin | This framework |
|---|---|---|---|---|
| Setup | k8s operator + CRDs | Separate process + config | Commercial agent | Add one dependency |
| Use in CI | Complex YAML | Manual wiring | Paid plan | `./gradlew test` |
| Type safety | None (YAML) | None (API) | None (UI/API) | Full Java types + IDE |
| syscall-level faults | ❌ | ❌ | ❌ | ✅ |
| JVM internals (carrier pinning, code cache, G1) | ❌ | ❌ | ❌ | ✅ |
| Named production incidents | ❌ | ❌ | Limited | ✅ 64 incidents |
| Any Docker image | ⚠️ | ❌ | ❌ | ✅ |
| Developer-owned test | ❌ | ❌ | ❌ | ✅ |

---

## Quick start

**1. Add the dependency for the tier you need**

```groovy
// build.gradle — pick one or more
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-jvm:1.0.0'      // JVM incidents
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kubernetes:1.0.0' // k8s incidents
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-cache:1.0.0'     // cache incidents
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-spring:1.0.0'    // Spring incidents
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kafka:1.0.0'     // Kafka incidents
```

**2. Write one test**

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
class MyServiceChaosTest {

    @Container @AppContainer
    static GenericContainer<?> app = new GenericContainer<>("my-app:latest")
        .withExposedPorts(8080);

    @Test
    @IncidentChaosK8sRollingUpdateRst
    void service_survives_rolling_deploy_rst() {
        // assert your service handles ECONNRESET on outbound calls
    }
}
```

**3. Run**

```bash
./gradlew test
```

No infrastructure changes. No CI pipeline modifications. No Docker Compose file.

---

## Showcase — what you can test in 30 seconds

### The one that kills JDK 21 apps silently

JVM code cache fills over days under load. The JIT compiler stops compiling new methods. The JVM falls back to the interpreter. Throughput drops 10–50×. No exception. No OutOfMemoryError. No log line. Just a gradual slowdown that looks like a traffic spike until it isn't.

```java
@AppContainer
@JvmAgentChaos
@IncidentChaosJvmCodeCacheExhaustion  // fills JIT code cache → JVM falls back to interpreter
class CodeCacheExhaustionTest {

    @Test
    void throughput_does_not_collapse_when_code_cache_is_full() {
        final long p99LatencyMs = measureP99LatencyMs(app, Duration.ofSeconds(10));
        // Without a fix, p99 climbs from 5ms to 250ms+ as interpreted code runs 10-50x slower
        assertThat(p99LatencyMs).isLessThan(50);
    }
}
```

This failure accumulated silently for weeks before the Atlassian Confluence incident. No other testing tool can reproduce it — it requires injecting synthetic classes directly into the JVM until the code cache segment fills.

---

### The one that looks like a traffic spike

CPU at 100%. Throughput at 30%. Health endpoint responds. No errors in logs. On-call spends 2 hours looking at dashboards before someone notices the safepoint log.

```java
@AppContainer
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
@JvmAgentChaos
@IncidentChaosJvmSafepointCascade
// What this annotation composes:
//   1. SafepointStormStressor — repeated System.gc() → real STW safepoint pauses
//   2. connection ECONNRESET — HikariCP connection validation fails during the pause
//   3. dns EAI_AGAIN — ZooKeeper session expiry because DNS fails during pause
//   All three fire simultaneously, the way they do in production.
class SafepointCascadeTest {

    @Test
    void connection_pool_recovers_after_safepoint_cascade() {
        // Start a safepoint storm in the background
        // Assert: HikariCP reconnects, ZK session re-established, no request loss
        assertThat(callService()).isEqualTo(200);
    }
}
```

---

### The one no one believes until their pod dies

G1 heap is at 60%. Application is running normally. OOM kill in the logs. No Java OOM. Exit code 137.

```java
@AppContainer
@SyscallLevelChaos(LibchaosLib.MEMORY)
@JvmAgentChaos
@IncidentChaosK8sOomKillMidGc
// G1 temporarily exceeds Xmx during GC region evacuation.
// RSS spikes above the cgroup memory limit → OOM kill from the kernel, not from the JVM.
// Heap metrics show 60% used. The JVM never threw OutOfMemoryError.
class OomKillMidGcTest {

    @Test
    void service_restarts_cleanly_after_oom_kill() {
        // Assert: liveness probe triggers restart, application recovers, no data corruption
        assertThat(serviceIsHealthy()).isTrue();
    }
}
```

---

### The one that freezes silently on JDK 21

All carrier threads pinned. Virtual threads waiting. Request queue growing. Zero thread pool warnings. Zero stack traces. Health endpoint times out. Pod killed. Root cause never found in logs.

```java
@AppContainer
@JvmAgentChaos
@IncidentChaosJvmCarrierPinning
// VirtualThreadCarrierPinningStressor creates daemon threads holding synchronized monitors.
// With all ForkJoinPool carriers pinned, scheduled virtual threads can't run.
// Only reproducible with JVM-internal access — no network tool touches this.
class CarrierPinningTest {

    @Test
    void request_processing_continues_under_carrier_saturation() {
        final var response = callService(Duration.ofSeconds(5));
        // Fails if the application uses synchronized on a hot path with virtual threads.
        // Fix: replace synchronized with ReentrantLock or restructure the code.
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
```

The annotation alone tells the story of the fix: if this test fails, you have a `synchronized` block on a hot path that will pin carriers in production under load.

---

### The one that corrupts data silently at 2am

Hazelcast network partition. Two partitions form. Both accept writes. Partition heals. One side's writes survive. The other side's writes are gone. The application logged zero errors throughout.

```java
@AppContainer
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
@IncidentChaosHazelcastSplitBrain
// connection ECONNRESET — member-to-member heartbeats fail → partition forms
// dns EAI_AGAIN — discovery fails → partitions can't find each other to merge
class HazelcastSplitBrainTest {

    @Test
    void distributed_map_survives_split_brain_without_data_loss() {
        writeToAllNodes(Map.of("order-42", "CONFIRMED"));
        triggerPartition();
        writeToPartitionA(Map.of("order-42", "SHIPPED"));
        writeToPartitionB(Map.of("order-42", "CANCELLED"));
        healPartition();

        // Only one write can survive. Assert your merge policy handles this correctly.
        final var order = readFromAnyNode("order-42");
        assertThat(order).isIn("SHIPPED", "CANCELLED");  // not missing, not duplicated
    }
}
```

---

### The Feign retry amplification bomb

One user request fails with ECONNREFUSED. Feign retries 3 times. Resilience4j retries 3 times. Nine upstream calls go out for one user request. Upstream is in brownout. All nine calls fail. The retry storm makes the brownout worse. Exponential traffic amplification from linear load.

```java
@AppContainer
@SyscallLevelChaos(LibchaosLib.NET)
@IncidentChaosFeignRetryAmplification
// ChaosConnectEconnrefused on 50% of connect() calls — simulates the upstream brownout.
// At Feign retry=3 × Resilience4j retry=3: 9 upstream calls per user request.
class FeignRetryAmplificationTest {

    @Test
    void retry_storm_does_not_amplify_during_brownout() {
        final var upstreamCallsBefore = upstreamCallCount();
        sendUserRequests(100);
        final var upstreamCallsAfter = upstreamCallCount();

        // Assert: circuit breaker engaged before 9x amplification.
        // Without a circuit breaker, 100 user requests → 900 upstream calls.
        assertThat(upstreamCallsAfter - upstreamCallsBefore).isLessThan(300);
    }
}
```

---

## Documentation

| Document | Description |
|---|---|
| [Getting Started](docs/getting-started.md) | Setup, Gradle/Maven coordinates, first test |
| [Scoping Guide](docs/scoping-guide.md) | Container selection, class/method scope, id() routing |
| [L3 Reference](docs/l3-reference.md) | All 64 named production incidents — start here |
| [L2 Reference](docs/l2-reference.md) | All 92 composite scenarios |
| [L1 Reference](docs/l1-reference.md) | All ~448 raw syscall primitives |
| [Redis Guide](docs/redis-guide.md) | @RedisStandalone, @RedisSentinel, chaos combinations |
| [Package Manager Reference](docs/PACKAGE_MANAGER_TECHNICAL_REFERENCE.md) | Universal distro detection, all 6 package managers |
| [Network Chaos Reference](docs/NETWORK_CHAOS_TECHNICAL_REFERENCE.md) | tc/netem internals, qdisc algorithms |

---

## Platform support

| Platform | Syscall chaos (L1/L2/L3) | Network tc/iptables | Notes |
|---|---|---|---|
| Linux (native) | ✅ | ✅ | Full support |
| GitHub Actions (ubuntu-latest) | ✅ | ✅ | Full support |
| GitLab CI (Linux runner) | ✅ | ✅ | Full support |
| Docker Desktop (macOS) | ✅ | ✅ | Full support via Docker VM |
| Docker Desktop (Windows) | ✅ | ✅ | Full support via Docker VM |
| Dev container (any OS) | ✅ | ✅ | Full support |

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for full text.

© 2026 Christian Schnapka / Macstab GmbH

---

## About the author

**Christian Schnapka** — Principal+ Embedded Engineer, 30 years experience in distributed systems, real-time systems, and chaos engineering.

[Macstab GmbH](https://macstab.com) | info@macstab.com | [GitHub](https://github.com/macstab)

---

Built on [Testcontainers](https://www.testcontainers.org/), [JUnit 5](https://junit.org/junit5/), and the Linux kernel's dynamic linker.
