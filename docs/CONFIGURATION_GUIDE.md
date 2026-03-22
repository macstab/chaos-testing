# Configuration Guide
**Complete Annotation Reference with Best Practices**

**Last Updated:** 2026-03-21  
**Author:** Christian Schnapka, Principal+ Engineer @ [Macstab](https://macstab.com)  
**Module:** `macstab-chaos-redis`

---

## Table of Contents

1. [@RedisStandalone — Single Instance](#redisstandalone--single-instance)
2. [@RedisSentinel — High Availability Cluster](#redissentinel--high-availability-cluster)
3. [@InstallPackages — Universal Package Installation](#installpackages--universal-package-installation)
4. [RedisManager — Programmatic Access](#redismanager--programmatic-access)
5. [Multi-Instance Patterns](#multi-instance-patterns)
6. [Network Chaos Integration](#network-chaos-integration)
7. [Best Practices & Anti-Patterns](#best-practices--anti-patterns)
8. [Performance Tuning](#performance-tuning)
9. [Troubleshooting](#troubleshooting)

---

## @RedisStandalone — Single Instance

### Purpose

Starts a standalone Redis container for integration tests using Testcontainers.

### All Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | String | `"default"` | Container ID (unique within test class) |
| `version` | String | `"7.4"` | Redis Docker image version tag |
| `port` | int | `0` | Exposed host port (0 = random, recommended for CI) |
| `args` | String[] | `[]` | Additional Redis server command-line arguments |
| `enableNetworkChaos` | boolean | `false` | Enable network chaos (latency, packet loss) |
| `packages` | String[] | `[]` | Packages to install (curl, jq, vim, etc.) |

### Minimal Usage

**Zero configuration:**
```java
@RedisStandalone
class MyRedisTest {
    @Test
    void test(RedisConnectionInfo info) {
        // Redis running on info.getHost():info.getPort()
    }
}
```

**What you get:**
- Redis 7.4 (latest stable)
- Random available port
- Standard configuration
- Automatic lifecycle (start/stop)

### Typical Usage

**Production-like configuration:**
```java
@RedisStandalone(
    version = "7.4",
    args = {
        "--maxmemory", "256mb",
        "--maxmemory-policy", "allkeys-lru",
        "--save", ""  // Disable persistence
    }
)
class CacheEvictionTest {
    @Test
    void testLRUEviction(RedisConnectionInfo redis) {
        // Test cache eviction with memory limit
    }
}
```

**What you get:**
- Redis 7.4
- 256MB memory limit
- LRU eviction policy
- No disk persistence (faster tests)

### Advanced Usage

**Network chaos + debugging tools:**
```java
@RedisStandalone(
    version = "7.4-alpine",
    packages = {"curl", "jq", "tcpdump"},
    enableNetworkChaos = true
)
class AdvancedTest {
    @Test
    void testUnderChaos(RedisConnectionInfo redis, ControlFacade control) {
        // Install network chaos
        control.network().injectLatency(redis.getContainer(), Duration.ofMillis(100));
        
        // Debug with tcpdump if needed
        redis.getContainer().execInContainer("tcpdump", "-i", "eth0", "-c", "10");
    }
}
```

### Version Selection

**Available versions:**
```java
@RedisStandalone(version = "7.4")         // Latest 7.x (Debian-based)
@RedisStandalone(version = "7.4-alpine")  // Alpine Linux (smaller)
@RedisStandalone(version = "7.2")         // Older stable
@RedisStandalone(version = "6.2")         // Legacy support
```

**Distribution trade-offs:**

| Version | Base | Size | Startup | Use Case |
|---------|------|------|---------|----------|
| `7.4` | Debian | 116MB | 1-2s | Standard tests |
| `7.4-alpine` | Alpine | 32MB | 0.5-1s | CI/CD (faster) |
| `7.2` | Debian | 113MB | 1-2s | Compatibility testing |

### Redis Arguments

**Common configurations:**

```java
// Authentication
args = {"--requirepass", "secret"}

// Memory limits
args = {"--maxmemory", "512mb", "--maxmemory-policy", "volatile-lru"}

// Performance tuning
args = {"--maxclients", "200", "--tcp-backlog", "511"}

// Disable persistence (faster tests)
args = {"--save", "", "--appendonly", "no"}

// Multiple settings
args = {
    "--maxmemory", "256mb",
    "--maxmemory-policy", "allkeys-lru",
    "--save", "",
    "--appendonly", "no"
}
```

### Port Configuration

**Random port (recommended):**
```java
@RedisStandalone(port = 0)  // Default
```
- Prevents port conflicts
- Safe for parallel tests
- CI/CD friendly

**Fixed port (debugging only):**
```java
@RedisStandalone(port = 6379)
```
- Easy to connect with `redis-cli` locally
- Fails if port already in use
- NOT safe for CI/CD

### Network Chaos

**Enable chaos testing:**
```java
@RedisStandalone(enableNetworkChaos = true)
class NetworkChaosTest {
    @Test
    void testSlowNetwork(RedisConnectionInfo redis, ControlFacade control) {
        // Inject 100ms latency
        control.network().injectLatency(redis.getContainer(), Duration.ofMillis(100));
        
        // Test application under degraded network
    }
}
```

**What it does:**
- Adds `NET_ADMIN` capability to container
- Auto-installs `iproute2` + `iptables` packages
- Enables latency injection, packet loss, partitions

**Security model:**
- ✅ Container-scoped only (network namespace isolation)
- ✅ Cannot affect host or other containers
- ❌ Does NOT use privileged mode

### Package Installation

**Install debugging tools:**
```java
@RedisStandalone(packages = {"curl", "jq", "vim"})
```

**Install network tools:**
```java
@RedisStandalone(
    packages = {"iproute2", "iptables"},
    enableNetworkChaos = true
)
```

**Supported distributions:**
- Debian/Ubuntu → `apt-get`
- Alpine → `apk`
- Fedora/RHEL → `dnf`
- CentOS 7 → `yum`
- Arch → `pacman`
- openSUSE → `zypper`

---

## @RedisSentinel — High Availability Cluster

### Purpose

Starts a full Redis Sentinel cluster (master + replicas + sentinels) for HA testing.

### All Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | String | `"default"` | Cluster ID (unique within test class) |
| `version` | String | `"7.4"` | Redis Docker image version tag |
| `masterName` | String | `"mymaster"` | Sentinel master name |
| `replicas` | int | `2` | Number of replica nodes |
| `sentinels` | int | `3` | Number of Sentinel monitors |
| `quorum` | int | `2` | Failover quorum (majority = (sentinels/2)+1) |
| `enableNetworkChaos` | boolean | `false` | Enable network chaos for ALL containers |
| `packages` | String[] | `[]` | Packages to install in ALL containers |

### Minimal Usage

**Zero configuration:**
```java
@RedisSentinel
class SentinelTest {
    @Test
    void testFailover(SentinelCluster cluster) {
        // 1 master + 2 replicas + 3 sentinels running
        // Quorum = 2 (majority of 3)
    }
}
```

**What you get:**
- 1 master
- 2 replicas
- 3 sentinels
- Quorum = 2
- Auto-configured replication
- Auto-configured Sentinel monitoring

### Typical Usage

**Production-like HA:**
```java
@RedisSentinel(
    masterName = "prod-master",
    replicas = 3,
    sentinels = 5,
    quorum = 3
)
class HATest {
    @Test
    void testHighAvailability(SentinelCluster cluster) {
        // 1 master + 3 replicas + 5 sentinels
        // Can tolerate 2 Sentinel failures (quorum=3)
        // Can tolerate 1 data node failure
    }
}
```

### Advanced Usage

**Network chaos + cross-region simulation:**
```java
@RedisSentinel(
    replicas = 2,
    sentinels = 3,
    packages = {"iproute2", "iptables", "tcpdump"},
    enableNetworkChaos = true
)
class CrossRegionTest {
    @Test
    void testReplicationLag(SentinelCluster cluster, ControlFacade control) {
        // Simulate cross-region latency
        GenericContainer<?> replicaEU = cluster.getReplicas().get(0);
        control.network().injectLatency(replicaEU, Duration.ofMillis(80));  // us-east → eu-west
        
        // Verify replication lag handling
    }
}
```

### Replica Configuration

**Minimal (development):**
```java
@RedisSentinel(replicas = 1, sentinels = 1, quorum = 1)
```
- Fastest startup (~3s)
- Minimal resources
- NOT production-realistic

**Balanced (testing):**
```java
@RedisSentinel(replicas = 2, sentinels = 3, quorum = 2)  // Default
```
- Production-realistic
- Real quorum voting
- ~5-7s startup

**Production-like:**
```java
@RedisSentinel(replicas = 3, sentinels = 5, quorum = 3)
```
- High availability
- Tolerates 2 failures
- ~8-12s startup

### Quorum Calculation

**Formula:** `quorum = (sentinels / 2) + 1` (majority)

**Examples:**

| Sentinels | Recommended Quorum | Tolerated Failures |
|-----------|-------------------|-------------------|
| 1 | 1 | 0 (no HA) |
| 3 | 2 | 1 |
| 5 | 3 | 2 |
| 7 | 4 | 3 |

**Anti-pattern:**
```java
@RedisSentinel(sentinels = 3, quorum = 1)  // ❌ Too low!
```
- Quorum too small (1 Sentinel can trigger failover)
- Risk of split-brain

**Correct:**
```java
@RedisSentinel(sentinels = 3, quorum = 2)  // ✅ Majority
```
- Requires 2 Sentinels to agree
- Safe from split-brain

### Platform Requirements

**Auto-disabled on macOS/Windows:**

```java
@RedisSentinel  // Includes @DisabledOnNonLinuxHost
class SentinelTest {
    // Automatically skipped on macOS/Windows
    // Runs on Linux or dev containers
}
```

**Why:**
- Sentinel advertises container IPs (172.18.0.5)
- macOS/Windows cannot route to container IPs (VM layer)
- Linux has native Docker networking

**Solution for macOS/Windows:**
Use dev container:
```bash
# VS Code: Command Palette → "Reopen in Container"
# OR: docker compose up -d (in .devcontainer/)
```

### Network Chaos (All Containers)

**Enable chaos for entire cluster:**
```java
@RedisSentinel(
    replicas = 2,
    sentinels = 3,
    enableNetworkChaos = true
)
```

**What it does:**
- Adds NET_ADMIN to master + replicas + sentinels
- Installs iproute2/iptables in ALL containers
- Enables per-container chaos

**Use cases:**
```java
// Slow replica
control.network().injectLatency(cluster.getReplicas().get(0), Duration.ofMillis(100));

// Lossy sentinel
control.network().injectPacketLoss(cluster.getSentinels().get(0), 0.05);

// Network partition
control.network().partitionFrom(cluster.getReplicas().get(0), cluster.getMaster());
```

### Package Installation (All Containers)

**Install in ALL containers:**
```java
@RedisSentinel(
    replicas = 2,
    sentinels = 3,
    packages = {"curl", "jq"}
)
```

**Installed in:**
- 1 master
- 2 replicas
- 3 sentinels
- **Total:** 6 containers

**Performance impact:**
- 6 containers × 3s install time = ~18s
- Use sparingly (only essential packages)

---

## @InstallPackages — Universal Package Installation

### Purpose

Install packages in ANY Testcontainer using universal package manager detection.

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | String[] | (required) | Package names to install |
| `verify` | boolean | `true` | Verify installation with `which` command |

### Basic Usage

**Install utilities:**
```java
@Container
@InstallPackages({"curl", "jq", "vim"})
GenericContainer<?> postgres = new GenericContainer<>("postgres:16");
```

**Install network tools:**
```java
@Container
@InstallPackages({"iproute2", "iptables"})
GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine");
```

### Verification

**Enabled (default):**
```java
@InstallPackages({"curl", "jq"})  // Runs: which curl && which jq
@InstallPackages(value = {"curl"}, verify = true)
```

**Disabled (package name ≠ binary name):**
```java
@InstallPackages(value = {"iproute2"}, verify = false)  // Binary is 'tc', 'ip'
@InstallPackages(value = {"postgresql-client"}, verify = false)  // Binary is 'psql'
```

### Supported Distributions

| Distribution | Package Manager | Example Image |
|--------------|----------------|---------------|
| Debian/Ubuntu | `apt-get` | `postgres:16`, `ubuntu:22.04` |
| Alpine | `apk` | `redis:7.4-alpine`, `nginx:alpine` |
| Fedora/RHEL 8+ | `dnf` | `fedora:39`, `ubi8` |
| CentOS 7 | `yum` | `centos:7` |
| Arch Linux | `pacman` | `archlinux:latest` |
| openSUSE | `zypper` | `opensuse/leap:15` |

### Performance

| Distribution | Install Time | Speedup (cached) |
|--------------|--------------|------------------|
| Debian/Ubuntu | 4-5s | 80% (layer cache) |
| Alpine | 2-3s | 90% (smaller index) |
| Fedora | 5-6s | 70% (larger cache) |

### Best Practices

**✅ Good:**
```java
// Minimal packages
@InstallPackages({"curl", "jq"})

// Disable verification for known mismatches
@InstallPackages(value = {"iproute2"}, verify = false)

// Combine with chaos
@InstallPackages({"iproute2", "iptables"})
@EnableNetworkChaos
```

**❌ Bad:**
```java
// Too many packages (slow tests)
@InstallPackages({"curl", "vim", "emacs", "htop", "git", "gcc", ...})

// Unnecessary verification disable
@InstallPackages(value = {"curl"}, verify = false)  // curl binary exists!

// Manual deduplication (framework handles it)
@InstallPackages({"curl", "jq", "curl"})  // Auto-deduplicated
```

---

## RedisManager — Programmatic Access

### Purpose

Programmatic access to containers started by annotations.

### Single Instance

**Default instance:**
```java
@RedisStandalone
class Test {
    @BeforeEach
    void setUp() {
        RedisConnectionInfo redis = RedisStandalone.INSTANCE.get();
        // redis.getHost(), redis.getPort()
    }
}
```

**Named instance:**
```java
@RedisStandalone(id = "master")
class Test {
    @BeforeEach
    void setUp() {
        RedisConnectionInfo redis = RedisStandalone.INSTANCE.get("master");
    }
}
```

### Multi-Instance

**Get all instances:**
```java
@RedisSentinel(id = "primary", replicas = 3)
@RedisSentinel(id = "secondary", replicas = 2)
class Test {
    @Test
    void testAll() {
        List<SentinelCluster> all = RedisSentinel.INSTANCE.getAll();
        assertThat(all).hasSize(2);
        
        // Declaration order preserved
        SentinelCluster primary = all.get(0);    // id="primary"
        SentinelCluster secondary = all.get(1);  // id="secondary"
    }
}
```

**Get by ID:**
```java
@Test
void testSpecific() {
    SentinelCluster primary = RedisSentinel.INSTANCE.get("primary");
    assertThat(primary.getReplicas()).hasSize(3);
}
```

### Smart Default Resolution

**Single instance (any ID):**
```java
@RedisSentinel(id = "my-cluster")  // Not "default"
class Test {
    @Test
    void test() {
        SentinelCluster cluster = RedisSentinel.INSTANCE.get();  // Works! (only one cluster)
    }
}
```

**Multiple instances (explicit default required):**
```java
@RedisSentinel(id = "default")  // Explicit required
@RedisSentinel(id = "secondary")
class Test {
    @Test
    void test() {
        SentinelCluster cluster = RedisSentinel.INSTANCE.get();  // Returns id="default"
    }
}
```

---

## Multi-Instance Patterns

### Standalone Multi-Instance

**Multiple Redis instances:**
```java
@RedisStandalone(id = "cache", version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
@RedisStandalone(id = "rate-limiter", version = "7.4")
class MultiInstanceTest {
    @Test
    void testAll() {
        List<RedisConnectionInfo> all = RedisStandalone.INSTANCE.getAll();
        assertThat(all).hasSize(3);
    }
}
```

**Use cases:**
- Cache + session store
- Rate limiter + pub/sub
- Multi-tenancy testing

### Sentinel Multi-Cluster

**Multiple HA clusters:**
```java
@RedisSentinel(id = "primary", replicas = 3, sentinels = 5)
@RedisSentinel(id = "secondary", replicas = 2, sentinels = 3)
class MultiClusterTest {
    @Test
    void testGeoDist() {
        SentinelCluster primary = RedisSentinel.INSTANCE.get("primary");
        SentinelCluster secondary = RedisSentinel.INSTANCE.get("secondary");
        
        // Test cross-cluster replication
    }
}
```

**Use cases:**
- Geo-distributed deployments
- Multi-region failover
- Read/write separation

### Mixed Topology

**Sentinel + Standalone:**
```java
@RedisSentinel(id = "ha-cluster", replicas = 2)
@RedisStandalone(id = "cache")
@RedisStandalone(id = "session")
class MixedTest {
    @Test
    void testMixed() {
        SentinelCluster ha = RedisSentinel.INSTANCE.get("ha-cluster");
        RedisConnectionInfo cache = RedisStandalone.INSTANCE.get("cache");
        RedisConnectionInfo session = RedisStandalone.INSTANCE.get("session");
        
        // Test application with HA + cache + sessions
    }
}
```

**Use cases:**
- Production-realistic architectures
- HA for critical data + cache for performance
- Mixed redundancy requirements

---

## Network Chaos Integration

### Latency Injection

**Fixed delay:**
```java
@RedisStandalone(enableNetworkChaos = true)
@Test
void testLatency(RedisConnectionInfo redis, ControlFacade control) {
    control.network().injectLatency(redis.getContainer(), Duration.ofMillis(100));
}
```

**Jitter (variable latency):**
```java
control.network().injectLatencyWithJitter(
    redis.getContainer(),
    Duration.ofMillis(50),  // Base delay
    Duration.ofMillis(20)   // ±20ms jitter → [30ms, 70ms]
);
```

### Packet Loss

**Random loss:**
```java
control.network().injectPacketLoss(redis.getContainer(), 0.05);  // 5% loss
```

**Burst loss (Gilbert-Elliott):**
```java
control.network().injectPacketLoss(
    redis.getContainer(),
    0.05,   // 5% average loss
    0.25    // 25% correlation (bursty)
);
```

### Network Partitions

**Isolate replica from master:**
```java
@RedisSentinel(replicas = 2, enableNetworkChaos = true)
@Test
void testPartition(SentinelCluster cluster, ControlFacade control) {
    control.network().partitionFrom(
        cluster.getReplicas().get(0),
        cluster.getMaster()
    );
    
    // Verify Sentinel detects partition + triggers failover
}
```

### Bandwidth Limiting

**Rate limit to 1 Mbps:**
```java
control.network().limitBandwidth(redis.getContainer(), "1mbit");
```

### Reset Chaos

**Remove all chaos:**
```java
control.network().reset(redis.getContainer());
```

**Reset all containers:**
```java
control.network().resetAll();
```

---

## Best Practices & Anti-Patterns

### Annotation Configuration

**✅ Good:**
```java
// Minimal configuration (fast tests)
@RedisStandalone

// Production-realistic (balanced)
@RedisSentinel(replicas = 2, sentinels = 3, quorum = 2)

// Explicit version pinning (reproducible)
@RedisStandalone(version = "7.4")

// Disable persistence (faster)
@RedisStandalone(args = {"--save", "", "--appendonly", "no"})
```

**❌ Bad:**
```java
// Fixed port (conflicts in CI)
@RedisStandalone(port = 6379)

// Too many replicas (slow tests)
@RedisSentinel(replicas = 10, sentinels = 15)  // Overkill!

// Latest tag (non-reproducible)
@RedisStandalone(version = "latest")  // Breaking changes!

// Enabled persistence (slower)
@RedisStandalone(args = {"--appendonly", "yes", "--save", "60 1000"})
```

### Package Installation

**✅ Good:**
```java
// Minimal packages
@RedisStandalone(packages = {"curl", "jq"})

// Network tools + chaos
@RedisStandalone(packages = {"iproute2", "iptables"}, enableNetworkChaos = true)

// Disable verification for known mismatches
@InstallPackages(value = {"iproute2"}, verify = false)
```

**❌ Bad:**
```java
// Too many packages
@RedisStandalone(packages = {"curl", "vim", "emacs", "htop", "git", "gcc", "make", ...})

// Redundant installation
@RedisStandalone(packages = {"iproute2", "iptables"}, enableNetworkChaos = true)
// enableNetworkChaos already installs these!
```

### Multi-Instance

**✅ Good:**
```java
// Clear IDs
@RedisStandalone(id = "cache")
@RedisStandalone(id = "session")

// Resource budget aware
@RedisSentinel(id = "primary", replicas = 2)
@RedisStandalone(id = "cache")
// Total: 6 + 1 = 7 containers (within limits)
```

**❌ Bad:**
```java
// Ambiguous IDs
@RedisStandalone(id = "redis1")
@RedisStandalone(id = "redis2")

// Exceeds resource budget
@RedisSentinel(id = "cluster1", replicas = 5, sentinels = 7)  // 13 containers
@RedisSentinel(id = "cluster2", replicas = 5, sentinels = 7)  // 13 containers
// Total: 26 containers (exceeds limit!)
```

### Network Chaos

**✅ Good:**
```java
// Explicit chaos operations
control.network().injectLatency(container, Duration.ofMillis(100));

// Reset after test
@AfterEach
void cleanup() {
    control.network().resetAll();
}

// Per-container chaos
control.network().injectLatency(replica1, Duration.ofMillis(80));
control.network().injectLatency(replica2, Duration.ofMillis(100));
```

**❌ Bad:**
```java
// Extreme values (unrealistic)
control.network().injectLatency(container, Duration.ofSeconds(60));  // 1 minute!

// No cleanup
@Test
void test() {
    control.network().injectPacketLoss(container, 0.99);  // 99% loss!
    // No reset → affects subsequent tests
}

// Chaos without enableNetworkChaos
@RedisStandalone  // Missing enableNetworkChaos=true
@Test
void test() {
    control.network().injectLatency(container, ...);  // FAILS (no NET_ADMIN)
}
```

---

## Performance Tuning

### Container Startup

**Fast (minimal):**
```java
@RedisStandalone  // 1-2s startup
```

**Slower (HA):**
```java
@RedisSentinel(replicas = 2, sentinels = 3)  // 5-7s startup
```

**Slow (HA + packages):**
```java
@RedisSentinel(replicas = 2, sentinels = 3, packages = {"curl", "jq"})  // 10-15s
```

### Parallel Startup

**Automatic for multi-instance:**
```java
@RedisStandalone(id = "instance1")
@RedisStandalone(id = "instance2")
@RedisStandalone(id = "instance3")
// 3 containers start in parallel (~2s instead of 6s)
```

### Disable Persistence

**Faster tests:**
```java
@RedisStandalone(args = {"--save", "", "--appendonly", "no"})
```

**Impact:**
- 20-30% faster writes
- No fsync() overhead
- Safe for ephemeral test data

### Image Selection

**Smallest/fastest:**
```java
@RedisStandalone(version = "7.4-alpine")  // 32MB, ~0.5s startup
```

**Standard:**
```java
@RedisStandalone(version = "7.4")  // 116MB, ~1s startup
```

---

## Troubleshooting

### Common Issues

**Issue: Tests skipped on macOS/Windows**
```
SentinelTest > testFailover() SKIPPED
Reason: Redis Sentinel tests require native Docker networking
```

**Solution:** Use dev container
```bash
# VS Code
Command Palette → "Reopen in Container"

# OR: Manual
cd .devcontainer
docker compose up -d
```

---

**Issue: Port conflicts**
```
ERROR: Port 6379 is already in use
```

**Solution:** Use random ports (default)
```java
@RedisStandalone(port = 0)  // Random port (default)
```

---

**Issue: Package installation fails**
```
PackageInstallationException: Package 'iproute2222' not found
```

**Solution:** Fix package name typo
```java
@RedisStandalone(packages = {"iproute2"})  // Correct spelling
```

---

**Issue: Package verification fails on Fedora/RHEL minimal images** ⚠️
```
PackageInstallationException: Package verification failed: 'iproute2' binary not found in PATH
exec: "which": executable file not found in $PATH
```

**Cause:** Fedora minimal images (`fedora:39`, `ubi9/ubi-minimal`) don't include `which` command

**Solution:** Install `which` first without verification:

```java
@RedisStandalone(
    image = "fedora:39",
    packages = {"which", "iproute2"}  // ✅ Include 'which' first
)
```

**OR** programmatically:
```java
@Test
void test() {
    // ✅ First install: 'which' + your packages, verification disabled
    PackageInstaller.install(container, List.of("which", "iproute2"), false);
    
    // ✅ Subsequent installs work normally
    PackageInstaller.install(container, "curl");  // verify=true (default)
}
```

**Affected:** Fedora minimal, RHEL UBI minimal, CentOS Stream minimal, Rocky minimal  
**Not affected:** Debian, Ubuntu, Alpine (include `which` by default)

---

**Issue: Network chaos fails**
```
NetworkChaosException: Container missing NET_ADMIN capability
```

**Solution:** Enable network chaos
```java
@RedisStandalone(enableNetworkChaos = true)  // Required!
```

---

**Issue: Resource budget exceeded**
```
ResourceBudgetExceededException: Too many containers: 26 > 20 (max)
```

**Solution:** Reduce replicas/sentinels or split into multiple test classes
```java
// Before: 2 large clusters (26 containers)
@RedisSentinel(id = "cluster1", replicas = 5, sentinels = 7)
@RedisSentinel(id = "cluster2", replicas = 5, sentinels = 7)

// After: Smaller clusters (14 containers)
@RedisSentinel(id = "cluster1", replicas = 2, sentinels = 3)
@RedisSentinel(id = "cluster2", replicas = 2, sentinels = 3)
```

---

**END OF CONFIGURATION GUIDE**

---

## About the Developer

**Christian Schnapka** — Principal+ Embedded Engineer with 30 years of experience specializing in configuration design, API ergonomics, and developer experience optimization.

- **Company:** [Macstab GmbH](https://macstab.com)
- **Email:** info@macstab.com

---

**Macstab Chaos Testing Framework** — © 2026 Christian Schnapka / Macstab GmbH  
**Document Version:** 1.0 | **Last Updated:** 2026-03-21  
Licensed under [Apache License 2.0](../LICENSE)
