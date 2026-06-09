# Redis Testing — Standalone, Sentinel, and Chaos

The `macstab-chaos-redis` module provides two container provisioning annotations: `@RedisStandalone` for single-node
Redis and `@RedisSentinel` for a full HA cluster (master + replicas + sentinel monitors). Both integrate with the chaos
framework so you can inject faults at the connection, network, or syscall layer on top of any Redis topology.

---

## `@RedisStandalone`

Starts a single Redis container before all tests in the class and stops it after all tests complete. The container is
shared across all tests in the class (singleton scope).

### Attributes

| Attribute               | Type       | Default     | Description                                                                                                                                                                                                                                                                                                                                                                |
|-------------------------|------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id`                    | `String`   | `"default"` | Unique identifier for this container within the test class. Required when multiple `@RedisStandalone` annotations are used on the same class.                                                                                                                                                                                                                              |
| `version`               | `String`   | `"7.4"`     | Docker image tag, e.g. `"7.4"`, `"7.2-alpine"`, `"6.2"`.                                                                                                                                                                                                                                                                                                                   |
| `port`                  | `int`      | `0`         | Host port to expose Redis on. `0` = random available port (recommended for CI).                                                                                                                                                                                                                                                                                            |
| `args`                  | `String[]` | `{}`        | Additional `redis-server` CLI arguments, e.g. `{"--maxmemory", "256mb"}`. Do not use `--port` here — the module resolves the mapped port via the default Redis port.                                                                                                                                                                                                       |
| `enableNetworkChaos`    | `boolean`  | `false`     | Adds `NET_ADMIN` capability to the container, enabling kernel-level network fault injection via `tc/netem` + `iptables`. Requires a Linux host or dev container.                                                                                                                                                                                                           |
| `enableConnectionChaos` | `boolean`  | `false`     | Injects `libchaos-net` via `LD_PRELOAD` before container start, enabling per-syscall errno injection on `connect`, `bind`, `accept`, `send`, `recv`, and `poll`. The Toxiproxy fallback inside `CompositeConnectionChaos` lazy-spawns on the first verb that requires it (e.g. bandwidth shaping). Orthogonal to `enableNetworkChaos` — both may be `true` simultaneously. |
| `packages`              | `String[]` | `{}`        | Packages to install in the container after it starts. The framework auto-detects the Linux distribution and selects the appropriate package manager (apt, apk, dnf, yum, pacman, zypper).                                                                                                                                                                                  |

### Minimal example

```java
@RedisStandalone
class BasicRedisTest {

    @Test
    void test(RedisConnectionInfo info) {
        // Redis running at info.getHost() : info.getPort()
        try (Jedis jedis = new Jedis(info.getHost(), info.getPort())) {
            jedis.set("key", "value");
            assertThat(jedis.get("key")).isEqualTo("value");
        }
    }
}
```

### Custom configuration

```java
@RedisStandalone(
    version = "7.4",
    args = {"--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"}
)
class CacheEvictionTest {
    // Redis 7.4 with LRU eviction policy
}
```

---

## `@RedisStandalone` repeated — multiple standalone instances

`@RedisStandalone` is a repeatable annotation. Place it multiple times on the same class to start independent Redis
instances. The Java compiler wraps them in `@RedisStandalones` automatically — you never write `@RedisStandalones`
directly.

```java
@RedisStandalone(id = "cache",   version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
@RedisStandalone(id = "rate-limiter")
class MultiInstanceTest {

    @Test
    void allInstancesAvailable() {
        StandaloneRedis cache       = RedisStandalone.INSTANCE.get("cache");
        StandaloneRedis session     = RedisStandalone.INSTANCE.get("session");
        StandaloneRedis rateLimiter = RedisStandalone.INSTANCE.get("rate-limiter");

        // or inject all at once:
        // List<RedisConnectionInfo> all = ... (parameter injection)
    }
}
```

Use multiple standalone instances when you need independent, isolated Redis nodes in the same test class — for example,
testing failover between two separate caches, verifying that data written to one instance is not visible in another, or
simulating multi-region independent stores. For genuine HA failover with Sentinel promotion, use `@RedisSentinel`
instead.

Up to 5 standalone instances may be started per test class (resource budget). All instances start in parallel.

---

## `@RedisSentinel`

Starts a full Redis Sentinel HA cluster: one master, N replicas, and M sentinel monitors, all connected on a shared
Docker network. The entire cluster is provisioned before all tests in the class and torn down afterwards.

### Platform requirement

⚠️ Redis Sentinel with Testcontainers requires native Docker host networking. This works on:

- ✅ Linux host (bare metal or VM)
- ✅ Dev containers and CI containers (even when the outer host is macOS or Windows)
- ❌ macOS host with Docker Desktop (uses a lightweight Linux VM that breaks host-mode networking)
- ❌ Windows host with Docker Desktop (WSL2/Hyper-V has the same restriction)

`@RedisSentinel` includes `@DisabledOnNonLinuxHost`, so tests are automatically **skipped** (not failed) on unsupported
hosts with a clear diagnostic message.

### Attributes

| Attribute               | Type       | Default      | Description                                                                                                                                                                                  |
|-------------------------|------------|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id`                    | `String`   | `"default"`  | Cluster identifier within the test class. Required when multiple `@RedisSentinel` annotations are used.                                                                                      |
| `version`               | `String`   | `"7.4"`      | Docker image tag for all cluster nodes.                                                                                                                                                      |
| `masterName`            | `String`   | `"mymaster"` | Sentinel master name used in `sentinel monitor` configuration and in client connection strings.                                                                                              |
| `replicas`              | `int`      | `2`          | Number of replica nodes (minimum 1 for HA).                                                                                                                                                  |
| `sentinels`             | `int`      | `3`          | Number of Sentinel monitor instances (3 recommended for real quorum).                                                                                                                        |
| `quorum`                | `int`      | `2`          | Minimum Sentinels that must agree before a failover is initiated. Recommended: `(sentinels / 2) + 1`.                                                                                        |
| `enableNetworkChaos`    | `boolean`  | `false`      | Adds `NET_ADMIN` capability to every node (master, replicas, sentinels). Enables per-container `tc/netem` latency, packet loss, and jitter injection.                                        |
| `enableConnectionChaos` | `boolean`  | `false`      | Injects `libchaos-net` via `LD_PRELOAD` into every node before start. Enables per-syscall errno injection at the libc socket layer on all cluster nodes. Orthogonal to `enableNetworkChaos`. |
| `packages`              | `String[]` | `{}`         | Packages installed in every node (master + all replicas + all sentinels) after the cluster starts.                                                                                           |

### Full example with Sentinel-aware client configuration

```java
@RedisSentinel(
    masterName = "ha-master",
    replicas   = 2,
    sentinels  = 3,
    quorum     = 2
)
class SentinelFailoverTest {

    @Test
    void clientConnectsViaSentinel() {
        SentinelRedis cluster = RedisSentinel.INSTANCE.get("default");

        Set<String> sentinelNodes = cluster.sentinels().stream()
            .map(node -> node.host() + ":" + node.port())
            .collect(Collectors.toSet());

        JedisSentinelPool pool = new JedisSentinelPool(
            cluster.masterName(),
            sentinelNodes
        );

        try (Jedis jedis = pool.getResource()) {
            jedis.set("ping", "pong");
            assertThat(jedis.get("ping")).isEqualTo("pong");
        }
    }
}
```

### Multiple Sentinel clusters

```java
@RedisSentinel(id = "primary",   replicas = 2, sentinels = 3)
@RedisSentinel(id = "secondary", replicas = 1, sentinels = 3)
class MultiClusterTest {

    @Test
    void bothClustersRunning() {
        SentinelRedis primary   = RedisSentinel.INSTANCE.get("primary");
        SentinelRedis secondary = RedisSentinel.INSTANCE.get("secondary");
        // ...
    }
}
```

Up to 3 Sentinel clusters may be started per test class. Clusters start in parallel (40–50% faster than sequential
startup).

---

## Chaos + Redis — the combination

The `macstab-chaos-testpacks-l3-redis` module provides L3 incident scenarios purpose-built for Redis: network flaps,
failover storms, OOM eviction pressure, clock drift, cache avalanche, and slowlog spikes. These compose multiple
low-level rules across connection, time, and memory domains simultaneously.

### Complete example

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET})
@RedisSentinel(masterName = "mymaster", replicas = 2, sentinels = 3)
class RedisSentinelChaosTest {

    @Test
    @IncidentChaosRedisNetworkFlap
    void sentinel_recovers_from_network_flap() {
        SentinelRedis cluster = RedisSentinel.INSTANCE.get("default");

        JedisSentinelPool pool = new JedisSentinelPool(
            cluster.masterName(),
            cluster.sentinels().stream()
                   .map(n -> n.host() + ":" + n.port())
                   .collect(Collectors.toSet())
        );

        // The network-flap scenario intermittently breaks connectivity between
        // master and replicas. Assert that the client reconnects and Sentinel
        // promotes a replica within your SLO window.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (Jedis jedis = pool.getResource()) {
                assertThat(jedis.ping()).isEqualTo("PONG");
            }
        });
    }
}
```

### Annotation responsibilities

| Annotation                                 | Role                                                                                            |
|--------------------------------------------|-------------------------------------------------------------------------------------------------|
| `@Testcontainers`                          | Testcontainers JUnit 5 lifecycle.                                                               |
| `@ExtendWith(ChaosTestingExtension.class)` | Activates chaos rule injection and teardown around each test.                                   |
| `@SyscallLevelChaos({LibchaosLib.NET})`    | Injects `libchaos-net` before container start. Required for network-syscall-level L3 scenarios. |
| `@RedisSentinel(...)`                      | Provisions the full HA cluster before the test class runs.                                      |
| `@IncidentChaosRedisNetworkFlap`           | Method-level L3 incident — applies the multi-domain flap scenario for this test only.           |

### Available L3 Redis incident annotations

| Annotation                          | What it simulates                                                                                     |
|-------------------------------------|-------------------------------------------------------------------------------------------------------|
| `@IncidentChaosRedisNetworkFlap`    | Intermittent connection breaks between cluster nodes, triggering Sentinel re-election.                |
| `@IncidentChaosRedisFailoverStorm`  | Rapid cascading failover — master loss followed by cascading replica promotion attempts.              |
| `@IncidentChaosRedisOomEviction`    | Memory pressure leading to aggressive key eviction (requires `enableNetworkChaos` or a memory limit). |
| `@IncidentChaosRedisClockDrift`     | Clock skew between nodes — exercises TTL expiry race conditions and Sentinel timeout miscalculation.  |
| `@IncidentChaosRedisCacheAvalanche` | Simultaneous key expiry storm under high read concurrency.                                            |
| `@IncidentChaosRedisSlowlog`        | Injected latency that pushes operations into slowlog territory.                                       |

---

## Dependency

**Gradle:**

```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-redis:1.0.0'
```

**Maven:**

```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-redis</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

For the L3 Redis incident annotations, also add:

**Gradle:**

```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-redis:1.0.0'
```

**Maven:**

```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-redis</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

## Deep-dive reference

For architecture internals, quorum mathematics, security model, performance analysis,
and the complete annotation-to-container execution path, see the full technical reference:

→ [Redis Testing Technical Reference](REDIS_TESTING_TECHNICAL_REFERENCE.md)
