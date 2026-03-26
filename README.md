# Macstab Chaos Testing Framework

**Industry-first annotation-driven chaos engineering for containerized integration tests.**

[![Maven Central](https://img.shields.io/maven-central/v/com.macstab.chaos/macstab-chaos-core)](https://search.maven.org/search?q=g:com.macstab.chaos)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Docker](https://img.shields.io/badge/Docker-20.10+-blue.svg)](https://www.docker.com/)

---

**Created by:** [Christian Schnapka](https://macstab.com), Principal+ Engineer @ [Macstab](https://macstab.com)  
**Organization:** [Macstab GmbH](https://macstab.com) | **Contact:** info@macstab.com  
**License:** [Apache 2.0](LICENSE)

---

---

## Why Chaos Testing Matters

### The Evolution of Complexity

**1990s — Monoliths:**  
Simple architectures. Unit tests were enough. Single datacenter. Predictable failure modes.

**2000s — Service-Oriented Architecture (SOA):**  
Multiple services. Integration tests became essential. Still mostly single-region.

**2007+ — Docker & Containerization:**  
Container orchestration. Testcontainers enabled realistic integration tests. Multi-region deployments started.

**2014+ — Kubernetes Era:**  
Distributed by default. Multi-zone replication. CDN layers. Blob storage across continents. Complex load balancing.

**TODAY — Hyper-Distributed Reality:**
- International replication (US-west ↔ EU-central latency: 150ms)
- Zone-redundant blob storage
- Multi-continent deployments
- Complex CDN routing
- Pod autoscaling and drainage
- Cross-region failover

### The Testing Gap

**Standard tests assume perfection:**
- Zero network latency
- Zero packet loss
- Instant failover
- Perfect replication

**Production reality:**
- Redis replication lag spikes to 300ms during pod drainage
- 5% packet loss on intercontinental links
- Network partitions during datacenter failovers
- CDN cache misses cascade to origin overload

### Real Failure Scenarios

**Scenario 1: Replication Lag During Node Shift**  
Your Redis replica in EU-central is 300ms behind master in US-west due to Kubernetes node drainage.

**Question:** Does your application handle stale reads? Or does it corrupt user sessions?

**Scenario 2: Packet Loss Spike**  
5% packet loss during cross-zone replication.

**Question:** Does your service retry correctly? Or does backpressure cascade and shut down your entire backend?

**Scenario 3: Latency Spike During High Load**  
Redis latency spikes to 200ms during Black Friday peak traffic.

**Question:** Can your system maintain SLA? Or do transactions fail and create invalid data?

### Existing Tools Are Too Complex

**Traditional Chaos Engineering (Chaos Monkey, Toxiproxy, etc.):**
- ✅ Production-grade chaos engineering
- ❌ Requires separate infrastructure
- ❌ Manual test orchestration
- ❌ 6-12 months setup time
- ❌ Limited to production testing (late feedback)

**This Framework:**
- ✅ Production-grade chaos engineering
- ✅ Zero infrastructure (uses Docker)
- ✅ Annotation-driven (declarative)
- ✅ 5 minutes setup time
- ✅ Tests fail in development (immediate feedback)

**Result:** Production-grade chaos testing — accessible to everyone.

---

## What Makes This Revolutionary

**This framework brings production chaos into your test environment — with one annotation.**

```java
@RedisSentinel(replicas = 2, sentinels = 3, enableNetworkChaos = true)
@Test
void testUnderChaos() {
    // Automatic cluster setup: 1 master + 2 replicas + 3 sentinels
    // Automatic network tools: tc + iptables installed
    // Ready for chaos testing in 5 seconds
}
```

**No other testing framework offers:**
- ✅ Annotation-driven network chaos (industry-first)
- ✅ Universal package manager (works on ANY Linux distro)
- ✅ Automatic Redis Sentinel orchestration
- ✅ Parallel container startup (40-50% faster)
- ✅ Network chaos + Redis testing integrated

---

## Three Independent Modules

### 1. **macstab-chaos-core** — Universal Container Utilities

**Problem:** Hardcoded package managers (`apt-get`, `apk`) break when container images change distributions.

**Solution:** Auto-detect Linux distribution and install packages universally.

```java
// Works on Debian, Ubuntu, Alpine, Fedora, CentOS, Arch, openSUSE
PackageManager.detect(container).install(container, "iproute2", "iptables");
```

**Innovation:** First-in-industry universal package manager for container testing.

📖 [**Technical Reference**](docs/PACKAGE_MANAGER_TECHNICAL_REFERENCE.md) — 51KB deep-dive (kernel-level analysis)

---

### 2. **macstab-chaos-network** — Network Chaos Engineering

**Problem:** Production networks have latency, packet loss, partitions. Tests assume perfection.

**Solution:** Inject realistic network conditions using Linux Traffic Control (`tc`).

```java
NetworkChaosController chaos = new NetworkChaosController(List.of(container));

// Simulate cross-region replication lag
chaos.injectLatency(container, Duration.ofMillis(100));

// Simulate unreliable WiFi
chaos.injectPacketLoss(container, 0.05);  // 5% loss

// Simulate network partition
chaos.partitionFrom(containerA, containerB);
```

**Innovation:** First framework with annotation-driven chaos for container tests.

**Capabilities:**
- **Latency injection:** Fixed delay, jitter, distribution-based (Pareto, normal)
- **Packet loss:** Random, burst (Gilbert-Elliott model)
- **Bandwidth limiting:** Token bucket (TBF), hierarchical (HTB)
- **Network partitioning:** Container isolation via iptables

📖 [**Technical Reference**](docs/NETWORK_CHAOS_TECHNICAL_REFERENCE.md) — 74KB deep-dive (netem kernel internals, qdisc algorithms)

---

### 3. **macstab-chaos-redis** — Redis Testing with Chaos

**Problem:** Testing Redis Sentinel requires 50-100 lines of Testcontainers boilerplate. No chaos testing support.

**Solution:** Single annotation starts complete Redis infrastructure with programmatic access.

#### Simple Standalone Redis

```java
@RedisStandalone(id = "cache", version = "7.4")
class MyTest {
    @Test
    void testCache() {
        // Type-safe programmatic access
        StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
        
        // Connect with Jedis
        try (Jedis jedis = new Jedis(cache.host(), cache.port())) {
            jedis.set("key", "value");
            assertThat(jedis.get("key")).isEqualTo("value");
        }
    }
}
```

#### Multi-Instance Redis

```java
@RedisStandalone(id = "cache", version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
@RedisStandalone(id = "rate-limiter", version = "7.4")
class MultiInstanceTest {
    
    // Option 1: List parameter injection (all instances)
    @Test
    void testAll(List<StandaloneRedis> all) {
        assertThat(all).hasSize(3);
        // Test data isolation across instances
    }
    
    // Option 2: Programmatic access (specific instance)
    @Test
    void testCache() {
        StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
        // Test cache-specific logic
    }
}
```

#### Redis Sentinel Cluster

```java
@RedisSentinel(id = "cluster", replicas = 2, sentinels = 3)
class SentinelTest {
    @Test
    void testFailover(SentinelRedis cluster) {
        // Cluster ready: 1 master + 2 replicas + 3 sentinels
        
        // Connect via JedisSentinelPool
        Set<String> sentinels = cluster.sentinels().stream()
            .map(endpoint -> endpoint.host() + ":" + endpoint.port())
            .collect(Collectors.toSet());
        
        JedisSentinelPool pool = new JedisSentinelPool(
            cluster.masterName(), 
            sentinels
        );
        
        // Test with automatic failover
        try (Jedis jedis = pool.getResource()) {
            jedis.set("key", "value");
        }
    }
}
```

**Capabilities:**
- **Standalone Redis:** Single instance with configurable version
- **Sentinel Clusters:** Automatic master + replicas + sentinels orchestration
- **Multi-Instance:** Parallel startup (40-50% faster than sequential)
- **Network Chaos Integration:** `enableNetworkChaos = true` adds NET_ADMIN + auto-installs tools
- **Custom Packages:** `@InstallPackages` annotation for additional tools
- **Command Tracking:** MONITOR-based verification (proves reads hit replicas)

📖 [**Technical Reference**](docs/REDIS_TESTING_TECHNICAL_REFERENCE.md) — 63KB deep-dive (JUnit 5 extension architecture, Sentinel orchestration)

---

## Quick Start

### Prerequisites

- **Java 21+** (JDK 21 or higher)
- **Docker 20.10+** (Docker Desktop or Docker Engine)
- **Linux host** or **dev container** (for network chaos + Sentinel tests)

### 1. Add Dependencies

**Maven:**
```xml
<!-- Core utilities (base module) -->
<dependency>
    <groupId>com.macstab.chaos</groupId>
    <artifactId>macstab-chaos-core</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>

<!-- Network chaos (optional) -->
<dependency>
    <groupId>com.macstab.chaos</groupId>
    <artifactId>macstab-chaos-network</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>

<!-- Redis testing (optional) -->
<dependency>
    <groupId>com.macstab.chaos</groupId>
    <artifactId>macstab-chaos-redis</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

**Gradle:**
```kotlin
testImplementation("com.macstab.chaos:macstab-chaos-core:1.0.0")
testImplementation("com.macstab.chaos:macstab-chaos-network:1.0.0")
testImplementation("com.macstab.chaos:macstab-chaos-redis:1.0.0")
```

### 2. Write Your First Chaos Test

**Standalone Redis with Network Chaos:**
```java
import com.macstab.chaos.redis.annotation.RedisStandalone;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

@RedisStandalone(enableNetworkChaos = true)
class RedisNetworkChaosTest {
    
    @Test
    void testSlowNetwork(GenericContainer<?> redis) throws Exception {
        NetworkChaosController chaos = new NetworkChaosController(List.of(redis));
        
        // Inject 100ms latency
        chaos.injectLatency(redis, Duration.ofMillis(100));
        
        // Your application tests here
        // Example: Verify timeouts, retry logic, etc.
    }
}
```

**Sentinel Cluster with Failover:**
```java
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.cluster.SentinelCluster;
import org.junit.jupiter.api.Test;

@RedisSentinel(replicas = 2, sentinels = 3)
class SentinelFailoverTest {
    
    @Test
    void testAutomaticFailover(SentinelCluster cluster) throws Exception {
        // 1 master + 2 replicas + 3 sentinels running
        
        // Stop master (simulate failure)
        cluster.getMaster().stop();
        
        // Wait for automatic promotion
        FailoverHelper.waitForPromotion(cluster, Duration.ofSeconds(30));
        
        // Verify new master elected
        assertThat(cluster.getCurrentMaster()).isNotNull();
    }
}
```

**Multi-Instance Testing:**
```java
@RedisStandalone(id = "cache", version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
@RedisStandalone(id = "rate-limiter", version = "7.4")
class MultiInstanceTest {
    
    @Test
    void testMultipleRedis(List<GenericContainer<?>> instances) {
        assertThat(instances).hasSize(3);
        
        // Test application with multiple Redis instances
        // Example: Cache + session store + rate limiter
    }
}
```

---

## Architecture Overview

### Module Dependencies

```
macstab-chaos-redis (Redis testing)
    ├─→ macstab-chaos-core (required)
    └─→ macstab-chaos-network (compileOnly - optional)

macstab-chaos-network (network chaos)
    └─→ macstab-chaos-core (required)

macstab-chaos-core (base utilities)
    └─→ Testcontainers (required)
```

**Modular Design:**
- Use only what you need
- Network chaos is optional
- Each module independently releasable

### System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Test Code                               │
│                                                             │
│  @RedisSentinel(replicas=2, sentinels=3,                    │
│                 enableNetworkChaos=true)                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              JUnit 5 Extension Framework                    │
│                                                             │
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │ Redis Extension │  │ Package Manager │                   │
│  │   - Orchestrate │  │   - Auto-detect │                   │
│  │   - Inject      │  │   - Install     │                   │
│  └────────┬────────┘  └────────┬────────┘                   │
│           │                    │                            │
│           ▼                    ▼                            │
│  ┌──────────────────────────────────────┐                   │
│  │   Network Chaos Controller           │                   │
│  │   - Latency, Loss, Partitions        │                   │
│  └────────┬─────────────────────────────┘                   │
└───────────┼─────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────┐
│                  Testcontainers                             │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │  Master  │  │ Replica  │  │ Sentinel │                   │
│  │          │─▶│          │─▶│          │                   │
│  └──────────┘  └──────────┘  └──────────┘                   │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                   Docker Daemon                             │
│                                                             │
│  ┌──────────────────────────────────────────────┐           │
│  │ Linux Container (Network Namespace)          │           │
│  │                                              │           │
│  │  ┌────────────┐     ┌──────────────┐         │           │
│  │  │ Redis      │────▶│ tc (netem)   │         │           │
│  │  │ Process    │     │ iptables     │         │           │
│  │  └────────────┘     └──────────────┘         │           │
│  └──────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────┘
```

---

## Feature Matrix

### Core Module (macstab-chaos-core)

| Feature | Description | Status |
|---------|-------------|--------|
| **Universal Package Manager** | Auto-detect Linux distro + install packages | ✅ Production |
| **Supported Distros** | Debian, Ubuntu, Alpine, Fedora, RHEL, CentOS, Arch, openSUSE | ✅ 6 managers |
| **Detection Methods** | `/etc/os-release` parsing + command fallback | ✅ Robust |
| **Thread Safety** | Concurrent operations on different containers | ✅ Safe |
| **Error Handling** | Clear diagnostics + retry suggestions | ✅ Complete |

### Network Chaos Module (macstab-chaos-network)

| Feature | Description | Status |
|---------|-------------|--------|
| **Latency Injection** | Fixed delay, jitter, distribution-based | ✅ Production |
| **Packet Loss** | Random, burst (Gilbert-Elliott model) | ✅ Production |
| **Bandwidth Limiting** | TBF (token bucket), HTB (hierarchical) | ✅ Production |
| **Network Partitioning** | iptables-based container isolation | ✅ Production |
| **Platform Support** | Linux host, Docker-in-Docker, dev containers | ✅ Universal |
| **Automatic Tool Installation** | iproute2 + iptables auto-installed | ✅ Zero-config |
| **Security Model** | NET_ADMIN capability, namespace isolation | ✅ Safe |

### Redis Testing Module (macstab-chaos-redis)

| Feature | Description | Status |
|---------|-------------|--------|
| **Standalone Redis** | Single instance with version pinning | ✅ Production |
| **Sentinel Clusters** | Master + replicas + sentinels (auto-quorum) | ✅ Production |
| **Multi-Instance** | Parallel startup (40-50% faster) | ✅ Optimized |
| **Network Chaos Integration** | `enableNetworkChaos = true` flag | ✅ Seamless |
| **Custom Packages** | `@InstallPackages` annotation | ✅ Flexible |
| **Command Tracking** | MONITOR-based verification | ✅ Observable |
| **Failover Helpers** | Trigger + wait for promotion | ✅ Helpers |
| **Resource Budget** | Prevent CI memory exhaustion | ✅ Safe |
| **Platform Detection** | Auto-skip Sentinel on macOS/Windows | ✅ Smart |

---

## Advanced Usage

### Network Chaos Scenarios

**Cross-Region Replication Lag:**
```java
@RedisSentinel(replicas = 2, sentinels = 3, enableNetworkChaos = true)
@Test
void testCrossRegionLag(SentinelCluster cluster) throws Exception {
    // Simulate 80ms latency (us-east-1 → eu-west-1)
    cluster.getControl().network().injectLatency(
        cluster.getReplicas().get(0),
        Duration.ofMillis(80)
    );
    
    // Verify replication lag handling
    Duration lag = RedisCommandTracker.measureReplicationLag(
        cluster.getMaster(),
        cluster.getReplicas().get(0)
    );
    
    assertThat(lag).isGreaterThan(Duration.ofMillis(80));
}
```

**Unreliable Mobile Network:**
```java
@Test
void testUnreliableNetwork(GenericContainer<?> redis) throws Exception {
    NetworkChaosController chaos = new NetworkChaosController(List.of(redis));
    
    // Simulate mobile network: 50ms latency + 5% packet loss
    chaos.injectLatencyWithJitter(redis, Duration.ofMillis(50), Duration.ofMillis(20));
    chaos.injectPacketLoss(redis, 0.05);
    
    // Verify retry logic, timeout handling
}
```

**Network Partition (Split-Brain):**
```java
@RedisSentinel(replicas = 2, sentinels = 3, enableNetworkChaos = true)
@Test
void testNetworkPartition(SentinelCluster cluster) throws Exception {
    // Isolate replica from master (simulate datacenter failure)
    cluster.getControl().network().partitionFrom(
        cluster.getReplicas().get(0),
        cluster.getMaster()
    );
    
    // Verify Sentinel detects partition + triggers failover
    FailoverHelper.waitForPromotion(cluster, Duration.ofSeconds(30));
}
```

### Custom Package Installation

**Install Debugging Tools:**
```java
@RedisStandalone(enableNetworkChaos = true)
@InstallPackages({"curl", "jq", "netcat", "tcpdump"})
@Test
void testWithDebugTools(GenericContainer<?> redis) throws Exception {
    // Tools auto-installed on startup
    
    // Use curl to test HTTP endpoints
    ExecResult result = redis.execInContainer("curl", "-s", "http://example.com");
    
    // Use jq to parse JSON
    redis.execInContainer("echo", "{\"key\":\"value\"}", "|", "jq", ".key");
    
    // Use tcpdump for network analysis
    redis.execInContainer("tcpdump", "-i", "eth0", "-c", "10");
}
```

### Command Tracking (Observability)

**Verify Read Routing to Replicas:**
```java
@RedisSentinel(replicas = 2, sentinels = 3)
@Test
void testReplicaReads(SentinelCluster cluster) throws Exception {
    RedisCommandTracker masterTracker = new RedisCommandTracker(cluster.getMaster());
    RedisCommandTracker replicaTracker = new RedisCommandTracker(cluster.getReplicas().get(0));
    
    masterTracker.start();
    replicaTracker.start();
    
    // Execute 1000 reads
    for (int i = 0; i < 1000; i++) {
        redisTemplate.opsForValue().get("key:" + i);
    }
    
    masterTracker.stop();
    replicaTracker.stop();
    
    // Verify 80%+ reads went to replica
    long masterReads = masterTracker.countCommand("GET");
    long replicaReads = replicaTracker.countCommand("GET");
    
    assertThat(replicaReads).isGreaterThan(masterReads * 4);  // 80%+ on replica
}
```

---

## Performance Characteristics

### Container Startup Times

| Configuration | Sequential | Parallel | Speedup |
|---------------|-----------|----------|---------|
| 1 Standalone | 1-2s | N/A | Baseline |
| 3 Standalone | 3-6s | 1-2s | **3× faster** |
| Sentinel (2+3) | 8-12s | 5-7s | **40% faster** |

### Resource Footprint

| Resource | Per Container | Sentinel Cluster | Notes |
|----------|--------------|------------------|-------|
| Memory | 15-30 MB | 150 MB (6 containers) | Empty dataset |
| CPU | <1% | <5% | Idle state |
| Disk | 10-50 MB | 300 MB | Image + layers |
| Startup | 1-2s | 5-7s | First run (cached image) |

### Network Chaos Overhead

| Operation | Latency | Impact |
|-----------|---------|--------|
| Setup (tc command) | 50-150ms | One-time |
| Per-packet enqueue | 1-4µs | Negligible |
| Total overhead | <1% throughput | Acceptable |

---

## Platform Support

### Supported Platforms

| Platform | Core | Network | Redis | Notes |
|----------|------|---------|-------|-------|
| **Linux (native)** | ✅ | ✅ | ✅ | Full support |
| **Docker Desktop (macOS)** | ✅ | ✅ | ⚠️ Standalone only | Sentinel requires dev container |
| **Docker Desktop (Windows)** | ✅ | ✅ | ⚠️ Standalone only | Sentinel requires dev container |
| **Dev Container (any OS)** | ✅ | ✅ | ✅ | Full support |
| **GitHub Actions (Linux)** | ✅ | ✅ | ✅ | Full support |
| **GitLab CI (Linux)** | ✅ | ✅ | ✅ | Full support |

**Why Sentinel requires Linux:**
- Sentinel advertises container IP (172.18.0.5) to clients
- macOS/Windows cannot route to container IPs (VM layer)
- Solution: Use dev container (runs inside Docker, sees container IPs)

### Dev Container Setup

**Instant Setup (VS Code):**
1. Install [Remote - Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)
2. Open project in VS Code
3. Click "Reopen in Container" (bottom-right notification)
4. Wait 2-3 minutes (one-time setup)
5. Run tests: `./gradlew test`

**Included in `.devcontainer/`:**
- Dockerfile (Java 21, Docker-in-Docker)
- docker-compose.yml (Docker daemon + volume mounts)
- devcontainer.json (VS Code settings)

---

## Documentation

### Technical References

Each module has deep technical documentation (50-75KB each) with:
- ✅ Complete architecture walkthrough
- ✅ End-to-end call flow (API → Kernel → Hardware)
- ✅ Algorithm specifications with source code references
- ✅ Security model with threat analysis
- ✅ Performance measurements with real data
- ✅ Every claim backed by spec references (RFC, JLS, kernel source)

**Available References:**
1. [**Package Manager Technical Reference**](docs/PACKAGE_MANAGER_TECHNICAL_REFERENCE.md) (51KB)
   - Universal package manager architecture
   - Detection algorithm (os-release parsing)
   - All 6 package managers (APT, APK, DNF, YUM, PACMAN, ZYPPER)
   - JVM → Docker → Kernel stack walkdown

2. [**Network Chaos Technical Reference**](docs/NETWORK_CHAOS_TECHNICAL_REFERENCE.md) (74KB)
   - Linux Traffic Control (tc) subsystem deep-dive
   - Qdisc algorithms (netem, TBF, HTB) with kernel source
   - Netlink protocol specification
   - 16-layer stack walkdown (JVM → NIC hardware)

3. [**Redis Testing Technical Reference**](docs/REDIS_TESTING_TECHNICAL_REFERENCE.md) (63KB)
   - JUnit 5 extension architecture
   - Sentinel orchestration flow
   - Multi-instance parallel startup
   - Resource budget enforcement

### Additional Documentation

- [Package Manager Configuration](docs/PACKAGE_MANAGER_CONFIGURATION.md)
- [Network Chaos Engineering](docs/NETWORK_CHAOS_ENGINEERING.md)
- [Redis Testing Reference](docs/REDIS_TESTING_TECHNICAL_REFERENCE.md)
- [Configuration Guide](docs/CONFIGURATION_GUIDE.md)

---

## Building

### Build All Modules

```bash
# Clean build
./gradlew clean build

# Skip tests (compile only)
./gradlew build -x test

# Run tests (requires Linux or dev container)
./gradlew test

# Run specific module tests
./gradlew :macstab-chaos-core:test
./gradlew :macstab-chaos-network:test
./gradlew :macstab-chaos-redis:test
```

### Publish

```bash
# Publish to Maven Local (~/.m2/repository)
./gradlew publishToMavenLocal

# Publish to Maven Central (requires credentials)
./gradlew publish
```

**Credentials Configuration:**

Create `~/.gradle/gradle.properties`:
```properties
# Maven Central (OSSRH)
ossrhUsername=<your-username>
ossrhPassword=<your-token>

# GPG Signing
signing.keyId=<your-key-id>
signing.password=<your-passphrase>
signing.secretKeyRingFile=/Users/<you>/.gnupg/secring.gpg
```

---

## Code Quality Standards

This project follows **Distinguished+ engineering standards:**

### Enforced by Build

- ✅ **Google Java Format** (Spotless plugin)
- ✅ **100% Javadoc coverage** for public APIs
- ✅ **Compiler warnings as errors** (`-Xlint:unchecked`, `-Xlint:deprecation`)
- ✅ **Null safety** (defensive programming, Objects.requireNonNull)
- ✅ **Immutability by default** (final classes, final fields)

### Code Review Checklist

**Before submitting PR:**
1. Run `./gradlew spotlessApply` (auto-format)
2. Run `./gradlew build` (no warnings/errors)
3. Add Javadoc for new public APIs
4. Add unit tests (90%+ coverage target)
5. Update documentation if needed

**Review Standards:**
- Distinguished+ Code Review standards
- All public APIs documented with Javadoc
- All magic values extracted to constants
- All exceptions include clear error messages
- All concurrent code justified with comments

---

## Contributing

Contributions welcome! This project follows:

1. **Fork & Pull Request** workflow
2. **Distinguished+ code quality** (see above)
3. **Comprehensive testing** (unit + integration)
4. **Clear commit messages** (conventional commits preferred)

**Good First Issues:**
- Add support for new package manager (e.g., Emerge for Gentoo)
- Add new network chaos scenario (e.g., packet corruption)
- Add Redis Cluster mode support (OSS Cluster)
- Improve error messages
- Add more examples to documentation

---

## Roadmap

### v1.0 (Current)
- ✅ Universal package manager (6 Linux distros)
- ✅ Network chaos (latency, loss, partitions, bandwidth)
- ✅ Redis Sentinel testing
- ✅ Multi-instance orchestration
- ✅ Parallel container startup

### v1.1 (Planned)
- ⏳ Redis Cluster mode support (slot-based sharding)
- ⏳ Packet corruption chaos (requires kernel module)
- ⏳ DNS resolution failures (custom DNS server)
- ⏳ Metrics export (Prometheus, Micrometer)
- ⏳ Performance benchmarking utilities

### v2.0 (Future)
- 🔮 Kubernetes support (Chaos Mesh integration)
- 🔮 Distributed tracing (OpenTelemetry)
- 🔮 Automated chaos scenarios (predefined patterns)
- 🔮 Chaos dashboard (visualization)

---

## License

**Apache License 2.0**

```
Copyright 2026 Christian Schnapka / Macstab GmbH

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

See [LICENSE](LICENSE) for full text.

---

## About the Developer

<div align="center">

### Christian Schnapka
**Principal+ Embedded Engineer | 30 Years Experience**

*Distributed Systems · Chaos Engineering · High-Performance Computing*

🏢 [**Macstab GmbH**](https://macstab.com) | 📧 info@macstab.com | 💻 [GitHub](https://github.com/macstab)

**Proud Demo Scene Member (1984-2004)** · Real-time Graphics · Assembly Optimization · C64 / Amiga ECS&AGA / x86

</div>

---

## Acknowledgments

Built with world-class open-source projects:

- [**Testcontainers**](https://www.testcontainers.org/) — Container lifecycle management
- [**JUnit 5**](https://junit.org/junit5/) — Testing framework
- [**Docker**](https://www.docker.com/) — Container runtime
- [**Linux Kernel**](https://kernel.org/) — Traffic Control subsystem
- [**iproute2**](https://wiki.linuxfoundation.org/networking/iproute2) — tc command suite
- [**Redis**](https://redis.io/) — In-memory data store

---

## Support

### Community

- **Issues:** [GitHub Issues](https://github.com/macstab/chaos-testing/issues)
- **Discussions:** [GitHub Discussions](https://github.com/macstab/chaos-testing/discussions)
- **Documentation:** [Technical References](docs/)

### Commercial Support

For enterprise support, training, or custom development:

- **Email:** info@macstab.com
- **Website:** [Macstab GmbH](https://macstab.com)

---

## Industry Impact

**This framework enables testing practices previously exclusive to tech giants.**

Traditional approach:
1. Manual chaos testing infrastructure (6-12 months development)
2. Separate chaos testing environment (high cost)
3. Manual test orchestration (error-prone)
4. Limited to production testing (late feedback)

**Macstab Chaos Testing Framework:**
1. Zero infrastructure (uses Docker)
2. Runs in CI/CD (GitHub Actions, GitLab CI)
3. Annotation-driven (declarative)
4. Tests fail in development (immediate feedback)

**Result:** **Production-grade chaos testing in 5 minutes.**

---

**⚡ Make your tests as resilient as production. Start chaos testing today.**

```bash
# Add to your project
./gradlew testImplementation("com.macstab.chaos:macstab-chaos-redis:1.0.0")

# Write one test
@RedisSentinel(enableNetworkChaos = true)
@Test void testUnderChaos() { }

# Watch it handle chaos
./gradlew test
```

**Welcome to production-realistic testing.** 🚀

---

<div align="center">

**Macstab Chaos Testing Framework**

*Production-Grade Chaos Engineering for Container Tests*

Created by **Christian Schnapka**, Principal+ Engineer @ [Macstab GmbH](https://macstab.com)

Licensed under [Apache License 2.0](LICENSE) | © 2026 Christian Schnapka / Macstab GmbH

[📖 Documentation](docs/) | [🐛 Issues](https://github.com/macstab/chaos-testing/issues) | [💬 Discussions](https://github.com/macstab/chaos-testing/discussions)

</div>
scussions](https://github.com/macstab/chaos-testing/discussions)

</div>
