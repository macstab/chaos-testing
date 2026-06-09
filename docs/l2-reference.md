# L2 Composite Annotation Reference

L2 annotations are named, single-domain chaos scenarios. Each one combines multiple L1
primitives into a real-world failure profile. Use L2 when you know which domain you want
to test and want a meaningful named scenario rather than a raw primitive.

All L2 annotations support:

- **`id`** — container filter; empty string targets all containers
- **`@Repeatable`** — multiple L2s can stack on the same test
- **Class or method scope**

---

## DNS (`macstab-chaos-testpacks-dns`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-dns:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-dns</artifactId>`

### `@CompositeChaosNxDomain`

**What it simulates:** The DNS resolver returns `EAI_NONAME` (NXDOMAIN) for every forward lookup, cutting all named
service dependencies as when an authoritative zone drops or a firewall blocks UDP 53.

| Attribute | Type     | Default | Description                                           |
|-----------|----------|---------|-------------------------------------------------------|
| `host`    | `String` | `"*"`   | Hostname to target; `"*"` matches all forward lookups |
| `id`      | `String` | `""`    | Container filter; empty = all containers              |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosNxDomain(host = "postgres")
void myTest() { ...}
```

---

### `@CompositeChaosDnsTimeout`

**What it simulates:** DNS resolution adds a configurable latency (default 8 s) before returning the correct answer,
testing whether connection timeouts fire before the pool is warm.

| Attribute   | Type     | Default | Description                                              |
|-------------|----------|---------|----------------------------------------------------------|
| `latencyMs` | `long`   | `8000`  | Added latency per DNS lookup in milliseconds             |
| `host`      | `String` | `"*"`   | Hostname to target; `"*"` applies to all forward lookups |
| `id`        | `String` | `""`    | Container filter; empty = all containers                 |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosDnsTimeout(latencyMs = 5000)
void myTest() { ...}
```

---

### `@CompositeChaosDnsTemporaryFailure`

**What it simulates:** The resolver returns `EAI_AGAIN` (SERVFAIL — retryable) for forward lookups, testing whether
retry logic is correct and whether retries cascade into a thundering herd.

| Attribute | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `host`    | `String` | `"*"`   | Hostname to target                       |
| `id`      | `String` | `""`    | Container filter; empty = all containers |

**Example:**

```java

@RedisStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosDnsTemporaryFailure
void myTest() { ...}
```

---

### `@CompositeChaosDnsBlackhole`

**What it simulates:** The resolver returns `EAI_FAIL` (hard, non-retryable failure) for all forward lookups, as when a
firewall silently drops DNS queries with no UDP response.

| Attribute | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `host`    | `String` | `"*"`   | Hostname to target                       |
| `id`      | `String` | `""`    | Container filter; empty = all containers |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosDnsBlackhole
void myTest() { ...}
```

---

### `@CompositeChaosDnsCachePoisoning`

**What it simulates:** Every forward DNS lookup is silently rewritten to resolve `redirectTo` instead of the real
target, mimicking a poisoned DNS cache or BGP hijack.

| Attribute    | Type     | Default       | Description                                 |
|--------------|----------|---------------|---------------------------------------------|
| `host`       | `String` | `"*"`         | Hostname whose lookups are rewritten        |
| `redirectTo` | `String` | `"localhost"` | Replacement hostname passed to the resolver |
| `id`         | `String` | `""`          | Container filter; empty = all containers    |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosDnsCachePoisoning(redirectTo = "localhost")
void myTest() { ...}
```

---

### `@CompositeChaosDnsServiceRedirection`

**What it simulates:** The service token in every forward lookup is replaced with an invalid name, causing `EAI_SERVICE`
or an unexpected port assignment as when a service registry entry is corrupted.

| Attribute     | Type     | Default         | Description                                     |
|---------------|----------|-----------------|-------------------------------------------------|
| `host`        | `String` | `"*"`           | Hostname to target                              |
| `serviceName` | `String` | `"invalid-svc"` | Replacement service name passed to the resolver |
| `id`          | `String` | `""`            | Container filter; empty = all containers        |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosDnsServiceRedirection(serviceName = "invalid-svc")
void myTest() { ...}
```

---

### `@CompositeChaosIpv6OnlyResolution`

**What it simulates:** DNS resolution strips all IPv4 (`A`) entries and returns only IPv6 (`AAAA`) answers, testing
dual-stack awareness and Happy Eyeballs logic.

| Attribute | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `host`    | `String` | `"*"`   | Hostname to apply IPv6-only filtering to |
| `id`      | `String` | `""`    | Container filter; empty = all containers |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosIpv6OnlyResolution
void myTest() { ...}
```

---

### `@CompositeChaosShuffledAnswerOrder`

**What it simulates:** The address list returned by every forward lookup is re-linked in random order before being
returned, exposing assumptions about first-answer stability in connection-pool stickiness logic.

| Attribute | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `host`    | `String` | `"*"`   | Hostname to shuffle answers for          |
| `id`      | `String` | `""`    | Container filter; empty = all containers |

**Example:**

```java

@RedisStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosShuffledAnswerOrder
void myTest() { ...}
```

---

### `@CompositeChaosReverseDnsFailure`

**What it simulates:** Every reverse DNS lookup (`getnameinfo()`) returns `EAI_NONAME`, testing whether the application
degrades gracefully when PTR records are unavailable — the common production state in cloud VPCs.

| Attribute | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `id`      | `String` | `""`    | Container filter; empty = all containers |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.DNS)
@CompositeChaosReverseDnsFailure
void myTest() { ...}
```

---

## Network/Connection (`macstab-chaos-testpacks-connection`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-connection:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-connection</artifactId>`

The `endpoint` attribute on all connection annotations accepts the following forms:

- `"*"` — wildcard matching every socket
- `"tcp4://host:port"` — TCP/IPv4 to a specific host and port
- `"tcp6://[host]:port"` — TCP/IPv6
- `"udp4://host:port"` — UDP/IPv4
- `"udp6://[host]:port"` — UDP/IPv6
- `"unix:///path"` — Unix-domain socket
- `"dns://hostname"` — DNS interception at `getaddrinfo` time
- `"hostname"` — shorthand for `dns://hostname`

### `@CompositeChaosConnectionRefused`

**What it simulates:** Every TCP `connect()` returns `ECONNREFUSED`, as when a service process has crashed but its
container is still scheduled and a firewall sends TCP RST.

| Attribute  | Type     | Default | Description                                                |
|------------|----------|---------|------------------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `ECONNREFUSED` fires per `connect()` call |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                             |
| `id`       | `String` | `""`    | Container filter; empty = all containers                   |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosConnectionRefused(toxicity = 0.8)
void myTest() { ...}
```

---

### `@CompositeChaosThunderingHerd`

**What it simulates:** A connection blackout followed by a thundering herd — all clients fail with `ECONNREFUSED` during
the test, then reconnect simultaneously when the rule is lifted, overwhelming the recovering service.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that the blackout fires per `connect()` |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                      |
| `id`       | `String` | `""`    | Container filter; empty = all containers            |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosThunderingHerd
void myTest() { ...}
```

---

### `@CompositeChaosUnreachableHost`

**What it simulates:** Every TCP `connect()` returns `EHOSTUNREACH` — no route to host — as during BGP route withdrawal
or a Kubernetes pod losing its CNI network attachment.

| Attribute  | Type     | Default | Description                                           |
|------------|----------|---------|-------------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `EHOSTUNREACH` fires per `connect()` |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                        |
| `id`       | `String` | `""`    | Container filter; empty = all containers              |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosUnreachableHost
void myTest() { ...}
```

---

### `@CompositeChaosUnreachableNetwork`

**What it simulates:** Every TCP `connect()` returns `ENETUNREACH` — no route to the entire network — as during VPC
peering misconfiguration or missing Transit Gateway route propagation.

| Attribute  | Type     | Default | Description                                          |
|------------|----------|---------|------------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `ENETUNREACH` fires per `connect()` |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                       |
| `id`       | `String` | `""`    | Container filter; empty = all containers             |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosUnreachableNetwork
void myTest() { ...}
```

---

### `@CompositeChaosSlowDownstream`

**What it simulates:** Downstream services are slow — connect calls are delayed by `latencyMs` ms and a fraction of send
calls fail with `EPIPE`, testing connection-timeout configuration and write-error handling.

| Attribute          | Type     | Default | Description                                             |
|--------------------|----------|---------|---------------------------------------------------------|
| `latencyMs`        | `long`   | `500`   | Connect latency in milliseconds                         |
| `sendFailToxicity` | `double` | `0.05`  | Probability that any given send call fails with `EPIPE` |
| `endpoint`         | `String` | `"*"`   | libchaos-net endpoint selector                          |
| `id`               | `String` | `""`    | Container filter; empty = all containers                |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosSlowDownstream(latencyMs = 2000, sendFailToxicity = 0.1)
void myTest() { ...}
```

---

### `@CompositeChaosTcpResetStorm`

**What it simulates:** Inbound `recv()` calls return corrupted data at a configurable rate, causing the
application-layer protocol to tear the connection with a TCP RST — producing a continuous RST storm under load.

| Attribute  | Type     | Default | Description                                                    |
|------------|----------|---------|----------------------------------------------------------------|
| `rate`     | `double` | `0.3`   | Fraction of bytes corrupted within each affected `recv()` call |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                                 |
| `id`       | `String` | `""`    | Container filter; empty = all containers                       |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosTcpResetStorm(rate = 0.3)
void myTest() { ...}
```

---

### `@CompositeChaosPortAlreadyInUse`

**What it simulates:** Every `bind()` returns `EADDRINUSE`, preventing server components from opening listening sockets
on startup — as after rapid restarts when `SO_REUSEADDR` is not set and the port is in `TIME_WAIT`.

| Attribute  | Type     | Default | Description                                           |
|------------|----------|---------|-------------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `EADDRINUSE` fires per `bind()` call |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                        |
| `id`       | `String` | `""`    | Container filter; empty = all containers              |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosPortAlreadyInUse
void myTest() { ...}
```

---

### `@CompositeChaosSocketEphemeralExhaustion`

**What it simulates:** Every `bind()` returns `EADDRNOTAVAIL` — the kernel cannot assign a local ephemeral port —
simulating ephemeral port exhaustion under extreme connection-creation load.

| Attribute  | Type     | Default | Description                                              |
|------------|----------|---------|----------------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `EADDRNOTAVAIL` fires per `bind()` call |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                           |
| `id`       | `String` | `""`    | Container filter; empty = all containers                 |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosSocketEphemeralExhaustion
void myTest() { ...}
```

---

### `@CompositeChaosAcceptStorm`

**What it simulates:** `accept()` calls fail with `EMFILE` at a high rate, simulating a server that has leaked file
descriptors until no new connections can be accepted.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `toxicity` | `double` | `0.8`   | Probability that `EMFILE` fires per `accept()` call |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                      |
| `id`       | `String` | `""`    | Container filter; empty = all containers            |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosAcceptStorm(toxicity = 0.8)
void myTest() { ...}
```

---

### `@CompositeChaosSendBufferStarvation`

**What it simulates:** Send calls fail with `ENOBUFS` — no kernel buffer space — as when a high-speed NIC's transmit
ring is full or the container network namespace is under backpressure.

| Attribute  | Type     | Default | Description                                    |
|------------|----------|---------|------------------------------------------------|
| `toxicity` | `double` | `0.5`   | Probability that `ENOBUFS` fires per send call |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                 |
| `id`       | `String` | `""`    | Container filter; empty = all containers       |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosSendBufferStarvation(toxicity = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosPollTimeout`

**What it simulates:** Every `poll()`/`epoll_wait()` call times out artificially after `timeoutMs` ms regardless of
actual socket readiness, testing whether event-driven I/O loops correctly handle spurious poll timeouts.

| Attribute   | Type     | Default | Description                                             |
|-------------|----------|---------|---------------------------------------------------------|
| `timeoutMs` | `long`   | `5000`  | Poll timeout duration in milliseconds                   |
| `toxicity`  | `double` | `1.0`   | Probability that a poll call is forced to timeout early |
| `endpoint`  | `String` | `"*"`   | libchaos-net endpoint selector                          |
| `id`        | `String` | `""`    | Container filter; empty = all containers                |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosPollTimeout(timeoutMs = 3000)
void myTest() { ...}
```

---

### `@CompositeChaosHalfOpenConnection`

**What it simulates:** Inbound `recv()` calls fail with `ECONNRESET`, simulating half-open TCP connections where the
remote end crashed without sending a FIN — exposing connection pools that lack validation-on-borrow.

| Attribute  | Type     | Default | Description                                           |
|------------|----------|---------|-------------------------------------------------------|
| `toxicity` | `double` | `0.3`   | Probability that `ECONNRESET` fires per `recv()` call |
| `endpoint` | `String` | `"*"`   | libchaos-net endpoint selector                        |
| `id`       | `String` | `""`    | Container filter; empty = all containers              |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.NET)
@CompositeChaosHalfOpenConnection(toxicity = 0.3)
void myTest() { ...}
```

---

## Memory (`macstab-chaos-testpacks-memory`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-memory:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-memory</artifactId>`

### `@CompositeChaosMemoryPressure`

**What it simulates:** Intermittent allocation pressure via `ENOMEM` on `mmap` at a low rate (default 5%), exercising
defensive allocation paths that are invisible in normal test suites.

| Attribute  | Type     | Default | Description                                       |
|------------|----------|---------|---------------------------------------------------|
| `toxicity` | `double` | `0.05`  | Probability that any `mmap` call returns `ENOMEM` |
| `id`       | `String` | `""`    | Container filter; empty = all containers          |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosMemoryPressure(toxicity = 0.05)
void myTest() { ...}
```

---

### `@CompositeChaosMemoryLeak`

**What it simulates:** Very-low-rate `ENOMEM` (default 0.1%) on `mmap`, designed for long-running soak tests that reveal
whether null-return code paths that are never hit in short runs are safe.

| Attribute  | Type     | Default | Description                                       |
|------------|----------|---------|---------------------------------------------------|
| `toxicity` | `double` | `0.001` | Probability that any `mmap` call returns `ENOMEM` |
| `id`       | `String` | `""`    | Container filter; empty = all containers          |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosMemoryLeak
void myTest() { ...}
```

---

### `@CompositeChaosThreadStackExhaustion`

**What it simulates:** Anonymous `mmap` calls fail with `ENOMEM` at 50% probability, making roughly half of all
`pthread_create()` (thread-stack) allocations fail — as when the process hits `RLIMIT_NPROC` or address space
fragmentation.

| Attribute  | Type     | Default | Description                                        |
|------------|----------|---------|----------------------------------------------------|
| `toxicity` | `double` | `0.5`   | Probability that anonymous `mmap` returns `ENOMEM` |
| `id`       | `String` | `""`    | Container filter; empty = all containers           |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosThreadStackExhaustion
void myTest() { ...}
```

---

### `@CompositeChaosJitCompilationFailure`

**What it simulates:** `mprotect` calls fail with `EACCES` at 80% probability, causing JIT compilers (JVM HotSpot, V8,
LuaJIT) that use `mprotect(PROT_EXEC)` to mark code pages executable to fall back to the interpreter or crash.

| Attribute  | Type     | Default | Description                                  |
|------------|----------|---------|----------------------------------------------|
| `toxicity` | `double` | `0.8`   | Probability that `mprotect` returns `EACCES` |
| `id`       | `String` | `""`    | Container filter; empty = all containers     |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosJitCompilationFailure
void myTest() { ...}
```

---

### `@CompositeChaosHugepageFailure`

**What it simulates:** `madvise` calls fail with `ENOMEM` at 30% probability, testing whether the application correctly
treats transparent hugepage advisory failures as non-fatal hints.

| Attribute  | Type     | Default | Description                                 |
|------------|----------|---------|---------------------------------------------|
| `toxicity` | `double` | `0.3`   | Probability that `madvise` returns `ENOMEM` |
| `id`       | `String` | `""`    | Container filter; empty = all containers    |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosHugepageFailure
void myTest() { ...}
```

---

### `@CompositeChaosJvmHeapPressure`

**What it simulates:** Anonymous `mmap` calls fail with `ENOMEM` at 10% probability, exercising the JVM heap-expansion
error handler and surfacing whether the service returns a proper error response on `OutOfMemoryError: Java heap space`.

| Attribute  | Type     | Default | Description                                        |
|------------|----------|---------|----------------------------------------------------|
| `toxicity` | `double` | `0.1`   | Probability that anonymous `mmap` returns `ENOMEM` |
| `id`       | `String` | `""`    | Container filter; empty = all containers           |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosJvmHeapPressure
void myTest() { ...}
```

---

### `@CompositeChaosOomKill`

**What it simulates:** Every interposed VM syscall (`mmap`, `mprotect`, `madvise`) fails with `ENOMEM` at 100%
probability, approximating the regime a process enters immediately before the Linux OOM killer fires.

| Attribute  | Type     | Default | Description                                                   |
|------------|----------|---------|---------------------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `ENOMEM` fires on each interposed VM syscall |
| `id`       | `String` | `""`    | Container filter; empty = all containers                      |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosOomKill
void myTest() { ...}
```

---

### `@CompositeChaosLibraryLoadFailure`

**What it simulates:** File-backed `mmap` calls fail with `EBADF` at 100% probability, breaking `dlopen()` and the
dynamic linker so that no shared library or JNI extension can be loaded — as when libraries are missing or have
permission errors.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that file-backed `mmap` returns `EBADF` |
| `id`       | `String` | `""`    | Container filter; empty = all containers            |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.MEMORY)
@CompositeChaosLibraryLoadFailure
void myTest() { ...}
```

---

## Process (`macstab-chaos-testpacks-process`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-process:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-process</artifactId>`

### `@CompositeChaosProcessLimitHit`

**What it simulates:** `fork()` fails with `EAGAIN` at a high rate, simulating `RLIMIT_NPROC` exhaustion where the OS
refuses to create new processes — testing whether worker spawn failure is handled gracefully.

| Attribute  | Type     | Default | Description                                       |
|------------|----------|---------|---------------------------------------------------|
| `toxicity` | `double` | `0.9`   | Probability that `EAGAIN` fires per `fork()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers          |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosProcessLimitHit(toxicity = 0.9)
void myTest() { ...}
```

---

### `@CompositeChaosThreadPoolExhaustion`

**What it simulates:** `pthread_create()` fails with `EAGAIN`, simulating OS-thread-count exhaustion that causes
executor services to reject tasks and reactive event loops to lose worker threads.

| Attribute  | Type     | Default | Description                                                 |
|------------|----------|---------|-------------------------------------------------------------|
| `toxicity` | `double` | `0.9`   | Probability that `EAGAIN` fires per `pthread_create()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers                    |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosThreadPoolExhaustion(toxicity = 0.9)
void myTest() { ...}
```

---

### `@CompositeChaosForkBomb`

**What it simulates:** `fork()` fails with `EAGAIN` at 95% probability, simulating the resource-starvation outcome of an
uncontrolled process-creation burst having saturated the process table.

| Attribute  | Type     | Default | Description                                       |
|------------|----------|---------|---------------------------------------------------|
| `toxicity` | `double` | `0.95`  | Probability that `EAGAIN` fires per `fork()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers          |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosForkBomb(toxicity = 0.95)
void myTest() { ...}
```

---

### `@CompositeChaosOomFork`

**What it simulates:** `fork()` fails with `ENOMEM` (rather than `EAGAIN`), testing whether the application
distinguishes the OOM-during-fork case from the process-limit case and applies appropriate retry policy.

| Attribute  | Type     | Default | Description                                       |
|------------|----------|---------|---------------------------------------------------|
| `toxicity` | `double` | `0.5`   | Probability that `ENOMEM` fires per `fork()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers          |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosOomFork(toxicity = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosGracefulShutdown`

**What it simulates:** `waitpid()` is delayed by `drainMs` ms, simulating a child process that takes a long time to
complete graceful shutdown and may exceed the orchestrator's `terminationGracePeriodSeconds`.

| Attribute | Type     | Default | Description                                          |
|-----------|----------|---------|------------------------------------------------------|
| `drainMs` | `long`   | `5000`  | Delay added to each `waitpid()` call in milliseconds |
| `id`      | `String` | `""`    | Container filter; empty = all containers             |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosGracefulShutdown(drainMs = 5000)
void myTest() { ...}
```

---

### `@CompositeChaosExecvePermissionDenied`

**What it simulates:** Every `execve()` fails with `EACCES`, preventing any external process from being launched — as
when a container's filesystem is mounted `noexec` or AppArmor/SELinux denies execution.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `EACCES` fires per `execve()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers            |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosExecvePermissionDenied
void myTest() { ...}
```

---

### `@CompositeChaosSpawnFailure`

**What it simulates:** `posix_spawn()` fails with `ENOMEM`, simulating the kernel failing to allocate memory for a new
process — testing retry and error-reporting for subprocess-based features.

| Attribute  | Type     | Default | Description                                              |
|------------|----------|---------|----------------------------------------------------------|
| `toxicity` | `double` | `0.5`   | Probability that `ENOMEM` fires per `posix_spawn()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers                 |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosSpawnFailure(toxicity = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosZombieAccumulation`

**What it simulates:** `waitpid()` fails with `ECHILD` at 70% probability, causing the parent to believe children exited
without reaping them — leading to zombie accumulation and eventual process-table exhaustion.

| Attribute  | Type     | Default | Description                                          |
|------------|----------|---------|------------------------------------------------------|
| `toxicity` | `double` | `0.7`   | Probability that `ECHILD` fires per `waitpid()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers             |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosZombieAccumulation(toxicity = 0.7)
void myTest() { ...}
```

---

### `@CompositeChaosSignalInterruption`

**What it simulates:** `waitpid()` fails with `EINTR` at 30% probability, testing whether the wait loop correctly
retries on signal interruption rather than treating it as a permanent failure.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `toxicity` | `double` | `0.3`   | Probability that `EINTR` fires per `waitpid()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers            |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosSignalInterruption(toxicity = 0.3)
void myTest() { ...}
```

---

### `@CompositeChaosExecveMemoryDenied`

**What it simulates:** `execve()` fails with `ENOMEM` — the kernel cannot map the new binary's address space — testing
whether the application distinguishes OOM exec failure from permission failure and retries appropriately.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `toxicity` | `double` | `0.5`   | Probability that `ENOMEM` fires per `execve()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers            |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosExecveMemoryDenied(toxicity = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosThreadCreateSlow`

**What it simulates:** Every `pthread_create()` is delayed by `latencyMs` ms, simulating slow thread initialisation on
heavily loaded NUMA systems or VMs whose host is under memory pressure.

| Attribute   | Type     | Default | Description                                                 |
|-------------|----------|---------|-------------------------------------------------------------|
| `latencyMs` | `long`   | `200`   | Delay added to each `pthread_create()` call in milliseconds |
| `id`        | `String` | `""`    | Container filter; empty = all containers                    |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosThreadCreateSlow(latencyMs = 200)
void myTest() { ...}
```

---

### `@CompositeChaosHardKill`

**What it simulates:** Every `waitpid()` returns `ESRCH` — no such process — simulating PID recycling where the parent
cannot determine whether child jobs finished, causing PID registry leaks and supervisor hangs.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that `ESRCH` fires per `waitpid()` call |
| `id`       | `String` | `""`    | Container filter; empty = all containers            |

**Example:**

```java

@AppContainer
@SyscallLevelChaos(LibchaosLib.PROCESS)
@CompositeChaosHardKill
void myTest() { ...}
```

---

## Time (`macstab-chaos-testpacks-time`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-time:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-time</artifactId>`

### `@CompositeChaosFrozenClock`

**What it simulates:** The system clock is pinned to a fixed point far in the past — time has stopped — causing
distributed locks to never expire, Raft elections to stall, and heartbeat timers to stop firing.

| Attribute | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `id`      | `String` | `""`    | Container filter; empty = all containers |

**Example:**

```java

@ZookeeperCluster
@SyscallLevelChaos(LibchaosLib.TIME)
@CompositeChaosFrozenClock
void myTest() { ...}
```

---

### `@CompositeChaosNanosleepInterruption`

**What it simulates:** `nanosleep()` returns `EINTR` at a configurable rate, testing whether the application's sleep
loop correctly handles signal interruption by retrying with the remaining time.

| Attribute  | Type     | Default | Description                                    |
|------------|----------|---------|------------------------------------------------|
| `toxicity` | `double` | `0.4`   | Probability that `nanosleep()` returns `EINTR` |
| `id`       | `String` | `""`    | Container filter; empty = all containers       |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.TIME)
@CompositeChaosNanosleepInterruption(toxicity = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosTimerCascade`

**What it simulates:** Every `nanosleep()` call returns `latencyMs` ms later than requested, simulating a CPU-starved
scheduler that causes chained deadline misses, premature heartbeat failures, and circuit-breaker misfires.

| Attribute   | Type     | Default | Description                                                                      |
|-------------|----------|---------|----------------------------------------------------------------------------------|
| `latencyMs` | `long`   | `100`   | Extra latency added to every `nanosleep()` call on top of the requested duration |
| `id`        | `String` | `""`    | Container filter; empty = all containers                                         |

**Example:**

```java

@KafkaCluster
@SyscallLevelChaos(LibchaosLib.TIME)
@CompositeChaosTimerCascade(latencyMs = 200)
void myTest() { ...}
```

---

### `@CompositeChaosClockSkew`

**What it simulates:** A sustained forward jump is applied to `CLOCK_REALTIME` (without touching `CLOCK_MONOTONIC`),
causing distributed lock TTL miscalculations, JWT validation inconsistencies, and Raft lease overruns.

| Attribute     | Type     | Default | Description                                                            |
|---------------|----------|---------|------------------------------------------------------------------------|
| `skewMs`      | `long`   | `500`   | Forward offset applied to `CLOCK_REALTIME` in milliseconds             |
| `probability` | `double` | `1.0`   | Probability that a given `clock_gettime` call returns the skewed value |
| `id`          | `String` | `""`    | Container filter; empty = all containers                               |

**Example:**

```java

@RedisStandalone
@SyscallLevelChaos(LibchaosLib.TIME)
@CompositeChaosClockSkew(skewMs = 2_000)
void myTest() { ...}
```

---

### `@CompositeChaosLeapSecond`

**What it simulates:** A canonical +1000 ms leap-second insertion on `CLOCK_REALTIME`, reproducing the JVM spin-loop,
Cassandra timeout storm, and scheduler instability seen during the 2012 and 2016 real-world leap seconds.

| Attribute     | Type     | Default | Description                                                                            |
|---------------|----------|---------|----------------------------------------------------------------------------------------|
| `probability` | `double` | `1.0`   | Probability that a given `clock_gettime(CLOCK_REALTIME)` returns the leap-second value |
| `id`          | `String` | `""`    | Container filter; empty = all containers                                               |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.TIME)
@CompositeChaosLeapSecond
void myTest() { ...}
```

---

### `@CompositeChaosTimeTravel`

**What it simulates:** `CLOCK_REALTIME` jumps backward by `skewMs` ms while `CLOCK_MONOTONIC` is untouched, causing JWTs
to appear expired, Redis TTLs to compute as negative, and Raft lease-based reads to fail.

| Attribute     | Type     | Default   | Description                                                                                |
|---------------|----------|-----------|--------------------------------------------------------------------------------------------|
| `skewMs`      | `long`   | `3600000` | Magnitude of the backward jump in milliseconds (1 hour default)                            |
| `probability` | `double` | `1.0`     | Probability that a given `clock_gettime(CLOCK_REALTIME)` returns the backward-jumped value |
| `id`          | `String` | `""`      | Container filter; empty = all containers                                                   |

**Example:**

```java

@RedisStandalone
@SyscallLevelChaos(LibchaosLib.TIME)
@CompositeChaosTimeTravel(skewMs = 3_600_000)
void myTest() { ...}
```

---

### `@CompositeChaosSlowMonotonic`

**What it simulates:** `CLOCK_MONOTONIC` consistently reads `skewMs` ms less than actual elapsed time, causing heartbeat
failure detectors to under-report elapsed time and retry backoff to be less aggressive than intended.

| Attribute     | Type     | Default | Description                                                                        |
|---------------|----------|---------|------------------------------------------------------------------------------------|
| `skewMs`      | `long`   | `250`   | How many milliseconds `CLOCK_MONOTONIC` appears to lag behind actual elapsed time  |
| `probability` | `double` | `1.0`   | Probability that a given `clock_gettime(CLOCK_MONOTONIC)` returns the skewed value |
| `id`          | `String` | `""`    | Container filter; empty = all containers                                           |

**Example:**

```java

@EtcdCluster
@SyscallLevelChaos(LibchaosLib.TIME)
@CompositeChaosSlowMonotonic(skewMs = 500)
void myTest() { ...}
```

---

## Filesystem (`macstab-chaos-testpacks-filesystem`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-filesystem:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-filesystem</artifactId>`

### `@CompositeChaosSlowDisk`

**What it simulates:** Both `read()` and `write()` on paths under the target prefix are delayed by `latencyMs` ms,
modelling a saturated block device such as an EBS gp2 volume with exhausted burst credits.

| Attribute   | Type     | Default | Description                                                              |
|-------------|----------|---------|--------------------------------------------------------------------------|
| `path`      | `String` | `"*"`   | Path prefix on which reads and writes are delayed; must start with `/`   |
| `latencyMs` | `long`   | `200`   | Delay in milliseconds injected before each matched `read()` or `write()` |
| `id`        | `String` | `""`    | Container filter; empty = all containers                                 |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosSlowDisk(path = "/data", latencyMs = 200)
void myTest() { ...}
```

---

### `@CompositeChaosWalFsyncDelay`

**What it simulates:** `fsync()` and `fdatasync()` calls are delayed by `latencyMs` ms, collapsing transaction
throughput on WAL-based databases (PostgreSQL, MySQL) as during EBS burst-credit exhaustion.

| Attribute   | Type     | Default | Description                                                                   |
|-------------|----------|---------|-------------------------------------------------------------------------------|
| `path`      | `String` | `"*"`   | Path prefix on which `fsync()` calls are delayed; must start with `/`         |
| `latencyMs` | `long`   | `500`   | Delay in milliseconds injected before each matched `fsync()` or `fdatasync()` |
| `id`        | `String` | `""`    | Container filter; empty = all containers                                      |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosWalFsyncDelay(path = "/var/lib/postgresql/wal", latencyMs = 500)
void myTest() { ...}
```

---

### `@CompositeChaosFdExhaustion`

**What it simulates:** Every `open()` returns `EMFILE` — the per-process file descriptor limit is reached — making it
impossible for the process to open any new file, socket, or pipe.

| Attribute  | Type     | Default | Description                                            |
|------------|----------|---------|--------------------------------------------------------|
| `toxicity` | `double` | `1.0`   | Probability that each `open()` returns `EMFILE`        |
| `path`     | `String` | `"*"`   | Path prefix to apply the fault to; must start with `/` |
| `id`       | `String` | `""`    | Container filter; empty = all containers               |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosFdExhaustion
void myTest() { ...}
```

---

### `@CompositeChaosEioOnRead`

**What it simulates:** A fraction of `read()` calls fail with `EIO`, modelling a partially-degraded storage device where
roughly half of sector reads fail — as from bad NVMe sectors or a degraded RAID member.

| Attribute  | Type     | Default | Description                                              |
|------------|----------|---------|----------------------------------------------------------|
| `path`     | `String` | `"*"`   | Path prefix on which reads may fail; must start with `/` |
| `toxicity` | `double` | `0.5`   | Probability that a matched read returns `EIO`            |
| `id`       | `String` | `""`    | Container filter; empty = all containers                 |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosEioOnRead(path = "/data/segments", toxicity = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosDiskFull`

**What it simulates:** Every `write()` on paths under the target prefix fails with `ENOSPC` — no space left on device —
as when a log volume, WAL partition, or Kubernetes PVC fills up.

| Attribute  | Type     | Default | Description                                           |
|------------|----------|---------|-------------------------------------------------------|
| `path`     | `String` | `"*"`   | Path prefix on which writes fail; must start with `/` |
| `toxicity` | `double` | `1.0`   | Probability that a matched write returns `ENOSPC`     |
| `id`       | `String` | `""`    | Container filter; empty = all containers              |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosDiskFull(path = "/var/lib/postgresql")
void myTest() { ...}
```

---

### `@CompositeChaosReadCorruption`

**What it simulates:** A single bit is flipped in the buffer returned by `read()` after libc returns successfully at a
very low rate (default 1%), modelling silent DRAM bit-flip or bad-sector data corruption.

| Attribute  | Type     | Default | Description                                                             |
|------------|----------|---------|-------------------------------------------------------------------------|
| `path`     | `String` | `"*"`   | Path prefix on which read buffers may be corrupted; must start with `/` |
| `toxicity` | `double` | `0.01`  | Probability that a matched read has one bit flipped                     |
| `id`       | `String` | `""`    | Container filter; empty = all containers                                |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosReadCorruption(path = "/data/store")
void myTest() { ...}
```

---

### `@CompositeChaosReadOnlyFilesystem`

**What it simulates:** All write-path syscalls (`write()`, `rename()`, `unlink()`) on paths under the target prefix fail
with `EACCES`, simulating the kernel remounting a filesystem read-only after detecting an unrecoverable I/O error.

| Attribute  | Type     | Default | Description                                                        |
|------------|----------|---------|--------------------------------------------------------------------|
| `path`     | `String` | `"*"`   | Path prefix to render read-only; must start with `/`               |
| `toxicity` | `double` | `1.0`   | Probability that each matched write/rename/unlink returns `EACCES` |
| `id`       | `String` | `""`    | Container filter; empty = all containers                           |

**Example:**

```java

@PostgresStandalone
@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosReadOnlyFilesystem(path = "/var/lib/postgresql")
void myTest() { ...}
```

---

### `@CompositeChaosRenameRace`

**What it simulates:** A fraction of `rename()` calls fail with `ENOENT` — the source file disappeared between `stat()`
and `rename()` — testing atomic rename error handling in WAL rotation, LSM compaction, and package installers.

| Attribute  | Type     | Default | Description                                                               |
|------------|----------|---------|---------------------------------------------------------------------------|
| `path`     | `String` | `"*"`   | Source-path prefix matched against the first argument of `rename()`       |
| `toxicity` | `double` | `0.5`   | Probability that a matched `rename()` source-path lookup returns `ENOENT` |
| `id`       | `String` | `""`    | Container filter; empty = all containers                                  |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosRenameRace(path = "/data/wal", toxicity = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosWriteCorruption`

**What it simulates:** A tiny fraction of `write()` calls succeed but silently write fewer bytes than requested (torn
write) at a very low rate (default 0.1%), modelling power-failure or NVMe controller reset during write — the root cause
of WAL corruption and SSTable header truncation.

| Attribute  | Type     | Default | Description                                                           |
|------------|----------|---------|-----------------------------------------------------------------------|
| `path`     | `String` | `"*"`   | Path prefix on which writes may be torn; must start with `/`          |
| `toxicity` | `double` | `0.001` | Probability that a matched write is torn (returns a short byte count) |
| `id`       | `String` | `""`    | Container filter; empty = all containers                              |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosWriteCorruption(path = "/data/wal", toxicity = 0.001)
void myTest() { ...}
```

---

### `@CompositeChaosMetadataFailure`

**What it simulates:** A fraction of `open()` calls on the target prefix fail with `EIO` — simulating inode or
filesystem-metadata read failure — as on a flash device with a worn-out inode region or after an unclean shutdown.

| Attribute  | Type     | Default | Description                                                       |
|------------|----------|---------|-------------------------------------------------------------------|
| `path`     | `String` | `"*"`   | Path prefix on which `open()` calls may fail; must start with `/` |
| `toxicity` | `double` | `0.3`   | Probability that a matched `open()` returns `EIO`                 |
| `id`       | `String` | `""`    | Container filter; empty = all containers                          |

**Example:**

```java

@SyscallLevelChaos(LibchaosLib.IO)
@CompositeChaosMetadataFailure(path = "/etc/app/config", toxicity = 0.3)
void myTest() { ...}
```

---

## JVM (`macstab-chaos-testpacks-java`)

**Gradle:** `testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-java:1.0.0'`  
**Maven:** `<artifactId>macstab-chaos-testpacks-java</artifactId>`

> The JVM testpack is particularly powerful — it includes stressor scenarios for heap pressure,
> GC amplification, code cache filling, virtual thread carrier pinning, and more. All annotations
> require the JVM chaos agent to be attached to the target container (`@JvmAgentChaos`).

### `@CompositeChaosGcPause`

**What it simulates:** A high allocation rate inside the JVM triggers frequent stop-the-world GC pauses, causing
P99/P999 latency spikes at GC boundaries — as in services performing excessive JSON deserialisation or string
concatenation.

| Attribute                      | Type     | Default     | Description                                                   |
|--------------------------------|----------|-------------|---------------------------------------------------------------|
| `durationMs`                   | `long`   | `500`       | How long the GC pressure stressor runs in milliseconds        |
| `allocationRateBytesPerSecond` | `long`   | `104857600` | Target allocation rate in bytes per second (default 100 MB/s) |
| `id`                           | `String` | `""`        | Container filter; empty = all JVM-agent containers            |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosGcPause(durationMs = 10_000)
void myTest() { ...}
```

---

### `@CompositeChaosDeadlock`

**What it simulates:** A permanent circular monitor deadlock among synthetic daemon threads, detectable via
`ThreadMXBean.findDeadlockedThreads()` — causing zero forward progress on deadlocked code paths and eventual thread-pool
exhaustion.

| Attribute     | Type     | Default | Description                                                            |
|---------------|----------|---------|------------------------------------------------------------------------|
| `threadCount` | `int`    | `2`     | Number of synthetic threads to deadlock in a circular ring (minimum 2) |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers                     |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosDeadlock(threadCount = 2)
void myTest() { ...}
```

---

### `@CompositeChaosThreadStarvation`

**What it simulates:** Platform threads are leaked by spawning `exhaustAfter` never-terminating daemon threads,
consuming OS-level thread slots until the application's thread-pool allocation fails with
`OutOfMemoryError: unable to create new native thread`.

| Attribute      | Type     | Default | Description                                        |
|----------------|----------|---------|----------------------------------------------------|
| `exhaustAfter` | `int`    | `10`    | Number of never-terminating daemon threads to leak |
| `id`           | `String` | `""`    | Container filter; empty = all JVM-agent containers |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosThreadStarvation(exhaustAfter = 10)
void myTest() { ...}
```

---

### `@CompositeChaosVirtualThreadPinning`

**What it simulates:** Virtual-thread carrier threads are pinned inside `synchronized` blocks for `durationMs` ms,
starving the carrier pool and preventing unmounted virtual threads from being scheduled — the canonical consequence of
using `synchronized` with blocking calls in virtual-thread code.

| Attribute           | Type     | Default | Description                                                      |
|---------------------|----------|---------|------------------------------------------------------------------|
| `durationMs`        | `long`   | `1000`  | How long each carrier thread is pinned per cycle in milliseconds |
| `pinnedThreadCount` | `int`    | `4`     | Number of carrier threads to pin simultaneously                  |
| `id`                | `String` | `""`    | Container filter; empty = all JVM-agent containers               |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosVirtualThreadPinning(durationMs = 1000)
void myTest() { ...}
```

---

### `@CompositeChaosSafepointStorm`

**What it simulates:** Repeated safepoint-forcing calls at `intervalMs` ms intervals saturate the JVM safepoint
mechanism, producing stop-the-world pauses up to 20 times per second and causing services to miss timeouts shorter than
the accumulated pause time.

| Attribute    | Type     | Default | Description                                              |
|--------------|----------|---------|----------------------------------------------------------|
| `intervalMs` | `long`   | `50`    | Interval between safepoint-forcing calls in milliseconds |
| `id`         | `String` | `""`    | Container filter; empty = all JVM-agent containers       |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosSafepointStorm(intervalMs = 50)
void myTest() { ...}
```

---

### `@CompositeChaosMonitorContention`

**What it simulates:** High monitor contention is sustained by `threadCount` threads competing for a single synthetic
lock in a tight loop, inflating OS-scheduler pressure, biased-lock revocation overhead, and latency P99/P999.

| Attribute     | Type     | Default | Description                                                  |
|---------------|----------|---------|--------------------------------------------------------------|
| `threadCount` | `int`    | `4`     | Number of threads competing for the shared synthetic monitor |
| `lockHoldMs`  | `long`   | `50`    | Per-thread lock-hold duration in milliseconds                |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers           |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosMonitorContention(threadCount = 4)
void myTest() { ...}
```

---

### `@CompositeChaosMetaspacePressure`

**What it simulates:** JVM Metaspace is filled to `targetMb` MB by generating and loading synthetic classes, simulating
the metaspace footprint of heavily reflective, CGLib-instrumented, or proxy-heavy applications and eventually triggering
`OutOfMemoryError: Metaspace`.

| Attribute  | Type     | Default | Description                                         |
|------------|----------|---------|-----------------------------------------------------|
| `targetMb` | `int`    | `32`    | Approximate Metaspace footprint target in megabytes |
| `id`       | `String` | `""`    | Container filter; empty = all JVM-agent containers  |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosMetaspacePressure(targetMb = 32)
void myTest() { ...}
```

---

### `@CompositeChaosDirectBufferLeak`

**What it simulates:** Direct (off-heap) NIO byte buffers totalling `targetMb` MB are allocated and retained, exhausting
the JVM direct-buffer limit and causing subsequent `ByteBuffer.allocateDirect()` calls to throw
`OutOfMemoryError: Direct buffer memory`.

| Attribute  | Type     | Default | Description                                                    |
|------------|----------|---------|----------------------------------------------------------------|
| `targetMb` | `int`    | `256`   | Total direct buffer memory to allocate and retain in megabytes |
| `id`       | `String` | `""`    | Container filter; empty = all JVM-agent containers             |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosDirectBufferLeak(targetMb = 256)
void myTest() { ...}
```

---

### `@CompositeChaosCodeCachePressure`

**What it simulates:** The JVM code cache is filled to `targetMb` MB by generating and JIT-compiling synthetic methods,
causing the JIT to disable itself and forcing subsequently loaded methods into interpreted mode — 10–100× slower.

| Attribute  | Type     | Default | Description                                          |
|------------|----------|---------|------------------------------------------------------|
| `targetMb` | `int`    | `16`    | Approximate code-cache footprint target in megabytes |
| `id`       | `String` | `""`    | Container filter; empty = all JVM-agent containers   |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosCodeCachePressure(targetMb = 16)
void myTest() { ...}
```

---

### `@CompositeChaosFinalizerBacklog`

**What it simulates:** The JVM `Finalizer` thread queue is flooded with `objectCount` objects whose `finalize()` methods
sleep briefly, backing up the single-threaded ReferenceHandler and delaying release of file descriptors and native
memory.

| Attribute     | Type     | Default | Description                                        |
|---------------|----------|---------|----------------------------------------------------|
| `objectCount` | `int`    | `10000` | Number of finaliser-carrying objects to enqueue    |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosFinalizerBacklog(objectCount = 10000)
void myTest() { ...}
```

---

### `@CompositeChaosStringInternStorm`

**What it simulates:** `stringCount` unique strings are interned into the JVM string pool, permanently pinning them and
causing the GC's weak-reference scanning phase to dominate pause time as the table grows.

| Attribute     | Type     | Default  | Description                                        |
|---------------|----------|----------|----------------------------------------------------|
| `stringCount` | `int`    | `100000` | Number of unique strings to intern                 |
| `id`          | `String` | `""`     | Container filter; empty = all JVM-agent containers |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosStringInternStorm(stringCount = 100000)
void myTest() { ...}
```

---

### `@CompositeChaosThreadLocalLeak`

**What it simulates:** Large values are leaked into `ThreadLocal` slots across `threadCount` threads, simulating the
heap accumulation of a thread-pool that never calls `ThreadLocal.remove()` between requests — a common pattern in
Servlet filters and MDC implementations.

| Attribute     | Type     | Default | Description                                            |
|---------------|----------|---------|--------------------------------------------------------|
| `threadCount` | `int`    | `50`    | Number of threads in which to leak thread-local values |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers     |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosThreadLocalLeak(threadCount = 50)
void myTest() { ...}
```

---

### `@CompositeChaosReferenceQueueFlood`

**What it simulates:** The JVM `ReferenceHandler` thread's queue is flooded with `objectCount` weak references, delaying
reclamation of soft- and weak-referenced objects and preventing the GC from recovering memory from unbounded weak/soft
reference caches.

| Attribute     | Type     | Default | Description                                        |
|---------------|----------|---------|----------------------------------------------------|
| `objectCount` | `int`    | `50000` | Number of weak references to enqueue               |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosReferenceQueueFlood(objectCount = 50000)
void myTest() { ...}
```

---

### `@CompositeChaosSpuriousWakeup`

**What it simulates:** `LockSupport.park()` and NIO `Selector.select()` return spuriously without being unparked or
having events arrive, exposing code that does not re-check wait conditions in a loop — a POSIX-documented possibility.

| Attribute     | Type     | Default | Description                                            |
|---------------|----------|---------|--------------------------------------------------------|
| `probability` | `double` | `0.2`   | Probability that a park/select call returns spuriously |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers     |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosSpuriousWakeup(probability = 0.2)
void myTest() { ...}
```

---

### `@CompositeChaosZipBomb`

**What it simulates:** `java.util.zip.Inflater` operations are intercepted to inject extreme expansion ratios,
simulating a zip-bomb payload that exhausts heap and CPU — validating that file-upload and decompression handlers impose
a size limit.

| Attribute     | Type     | Default | Description                                                       |
|---------------|----------|---------|-------------------------------------------------------------------|
| `probability` | `double` | `0.5`   | Probability that an inflater operation triggers extreme expansion |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers                |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosZipBomb(probability = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosConnectionPoolExhaustion`

**What it simulates:** Every `DataSource.getConnection()` call is delayed by `acquireDelayMs` ms, simulating an
exhausted or very slow JDBC connection pool — causing pool-timeout errors and 503s if the timeout is shorter than the
delay.

| Attribute        | Type     | Default | Description                                                          |
|------------------|----------|---------|----------------------------------------------------------------------|
| `acquireDelayMs` | `long`   | `5000`  | Delay injected before each `getConnection()` returns in milliseconds |
| `id`             | `String` | `""`    | Container filter; empty = all JVM-agent containers                   |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosConnectionPoolExhaustion(acquireDelayMs = 5000)
void myTest() { ...}
```

---

### `@CompositeChaosSlowQuery`

**What it simulates:** Every `Connection.commit()` is delayed by `commitDelayMs` ms, simulating a database under I/O
pressure where WAL sync is slow — causing connection exhaustion and ORM statement timeouts.

| Attribute       | Type     | Default | Description                                                   |
|-----------------|----------|---------|---------------------------------------------------------------|
| `commitDelayMs` | `long`   | `2000`  | Delay injected before each `commit()` returns in milliseconds |
| `id`            | `String` | `""`    | Container filter; empty = all JVM-agent containers            |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosSlowQuery(commitDelayMs = 2000)
void myTest() { ...}
```

---

### `@CompositeChaosJdbcRollbackFailure`

**What it simulates:** `Connection.rollback()` throws `SQLException` at a configurable probability, testing whether the
application handles rollback failure without leaving partial writes outstanding — one of the most underappreciated JDBC
failure modes.

| Attribute     | Type     | Default | Description                                                |
|---------------|----------|---------|------------------------------------------------------------|
| `probability` | `double` | `0.5`   | Probability that a `rollback()` call throws `SQLException` |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers         |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosJdbcRollbackFailure(probability = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosHttpClientCascade`

**What it simulates:** Every `HttpClient.send()` call is delayed by `delayMs` ms, simulating a slow downstream HTTP
dependency and triggering the classic cascading failure where blocking threads fill the thread pool until the service
becomes unresponsive.

| Attribute | Type     | Default | Description                                                            |
|-----------|----------|---------|------------------------------------------------------------------------|
| `delayMs` | `long`   | `3000`  | Delay injected before each `HttpClient.send()` returns in milliseconds |
| `id`      | `String` | `""`    | Container filter; empty = all JVM-agent containers                     |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosHttpClientCascade(delayMs = 3000)
void myTest() { ...}
```

---

### `@CompositeChaosHttpServerError5xx`

**What it simulates:** `HttpClient.send()` throws an `IOException` at a configurable probability, simulating a
downstream service that is overloaded and aborting connections — validating retry logic and graceful degradation.

| Attribute     | Type     | Default | Description                                                      |
|---------------|----------|---------|------------------------------------------------------------------|
| `probability` | `double` | `0.5`   | Probability that an `HttpClient.send()` call throws an exception |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers               |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosHttpServerError5xx(probability = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosShutdownHookHang`

**What it simulates:** `Runtime.addShutdownHook()` registration is delayed by `hangMs` ms, simulating a JVM that hangs
during the shutdown sequence — causing resources protected by shutdown hooks to be unreleased when Kubernetes SIGKILL
fires.

| Attribute | Type     | Default | Description                                                               |
|-----------|----------|---------|---------------------------------------------------------------------------|
| `hangMs`  | `long`   | `10000` | Delay injected when `Runtime.addShutdownHook()` is called in milliseconds |
| `id`      | `String` | `""`    | Container filter; empty = all JVM-agent containers                        |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosShutdownHookHang(hangMs = 10000)
void myTest() { ...}
```

---

### `@CompositeChaosScheduledTaskMissed`

**What it simulates:** `ScheduledExecutorService` task ticks are suppressed at a configurable probability, simulating
heartbeat, cache-eviction, or lease-renewal tasks that are silently dropped — causing expired leases, stale caches, or
missing metric flushes.

| Attribute     | Type     | Default | Description                                          |
|---------------|----------|---------|------------------------------------------------------|
| `probability` | `double` | `0.5`   | Probability that a scheduled task tick is suppressed |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers   |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosScheduledTaskMissed(probability = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosBlockingQueueOverflow`

**What it simulates:** `BlockingQueue.offer()` returns `false` (queue-full) at a configurable probability, simulating a
bounded queue saturated by a slow consumer — testing whether producers apply back-pressure or silently discard events.

| Attribute     | Type     | Default | Description                                              |
|---------------|----------|---------|----------------------------------------------------------|
| `probability` | `double` | `0.7`   | Probability that a `BlockingQueue.offer()` is suppressed |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers       |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosBlockingQueueOverflow(probability = 0.7)
void myTest() { ...}
```

---

### `@CompositeChaosCompletableFutureCancellation`

**What it simulates:** `CompletableFuture.cancel()` calls are delayed at a configurable probability, simulating slow
cancellation propagation that leaves phantom work consuming resources after the caller has given up.

| Attribute     | Type     | Default | Description                                                |
|---------------|----------|---------|------------------------------------------------------------|
| `probability` | `double` | `0.4`   | Probability that a `CompletableFuture.cancel()` is delayed |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers         |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosCompletableFutureCancellation(probability = 0.4)
void myTest() { ...}
```

---

### `@CompositeChaosClassLoadFailure`

**What it simulates:** `ClassNotFoundException` or `NoClassDefFoundError` is injected into class-loading operations
matching `classPattern`, simulating a corrupted class path, missing JAR, or class removed between build and deploy.

| Attribute      | Type     | Default | Description                                                    |
|----------------|----------|---------|----------------------------------------------------------------|
| `classPattern` | `String` | `"*"`   | Class name pattern to target (prefix or `"*"` for all classes) |
| `id`           | `String` | `""`    | Container filter; empty = all JVM-agent containers             |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosClassLoadFailure(classPattern = "com.example.plugin")
void myTest() { ...}
```

---

### `@CompositeChaosJndiInjection`

**What it simulates:** Every `InitialContext.lookup()` throws `NamingException`, simulating a missing or unreachable
JNDI server — as when a JMS broker is down, an EJB remote reference is unavailable, or a DataSource JNDI name is
mis-configured.

| Attribute     | Type     | Default | Description                                             |
|---------------|----------|---------|---------------------------------------------------------|
| `probability` | `double` | `1.0`   | Probability that a JNDI lookup throws `NamingException` |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers      |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosJndiInjection(probability = 1.0)
void myTest() { ...}
```

---

### `@CompositeChaosUnsafeDeserialization`

**What it simulates:** `ObjectInputStream.readObject()` throws `InvalidClassException` at a configurable probability,
simulating a corrupt or tampered serialised payload — validating that the application rejects malformed data cleanly
without exposing gadget-chain attack surfaces.

| Attribute     | Type     | Default | Description                                                    |
|---------------|----------|---------|----------------------------------------------------------------|
| `probability` | `double` | `0.5`   | Probability that `readObject()` throws `InvalidClassException` |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers             |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosUnsafeDeserialization(probability = 0.5)
void myTest() { ...}
```

---

### `@CompositeChaosNativeLibraryLoadFailure`

**What it simulates:** `System.loadLibrary()` and `System.load()` throw `UnsatisfiedLinkError`, simulating a missing or
incompatible native library (.so/.dll) — testing whether the application falls back to a pure-Java implementation or
fails gracefully.

| Attribute     | Type     | Default | Description                                                          |
|---------------|----------|---------|----------------------------------------------------------------------|
| `probability` | `double` | `1.0`   | Probability that a native library load throws `UnsatisfiedLinkError` |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers                   |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosNativeLibraryLoadFailure(probability = 1.0)
void myTest() { ...}
```

---

### `@CompositeChaosJmxInvocationStorm`

**What it simulates:** `MBeanServer.invoke()` throws `ReflectionException` at a configurable probability, simulating a
JMX management server that is unresponsive or returning errors — validating monitoring continuity and graceful fallback
for configuration-via-JMX code paths.

| Attribute     | Type     | Default | Description                                        |
|---------------|----------|---------|----------------------------------------------------|
| `probability` | `double` | `0.3`   | Probability that an `MBeanServer.invoke()` throws  |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosJmxInvocationStorm(probability = 0.3)
void myTest() { ...}
```

---

### `@CompositeChaosSslHandshakeFailure`

**What it simulates:** TLS/SSL handshakes fail with `SSLHandshakeException` at a configurable probability, simulating
expired certificates, cipher-suite mismatches, or a TLS termination proxy that is temporarily unavailable.

| Attribute     | Type     | Default | Description                                                     |
|---------------|----------|---------|-----------------------------------------------------------------|
| `probability` | `double` | `0.7`   | Probability that a TLS handshake throws `SSLHandshakeException` |
| `id`          | `String` | `""`    | Container filter; empty = all JVM-agent containers              |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosSslHandshakeFailure(probability = 0.7)
void myTest() { ...}
```

---

### `@CompositeChaosClockSkewInProcess`

**What it simulates:** The in-process JVM clock (`Instant.now()`, `System.currentTimeMillis()`, `LocalDateTime.now()`)
is skewed by `skewMs` ms, simulating NTP drift — causing JWT expiry validation failures, distributed lock TTL
miscalculations, and OAuth token invalidation.

| Attribute | Type     | Default | Description                                                    |
|-----------|----------|---------|----------------------------------------------------------------|
| `skewMs`  | `long`   | `500`   | Clock skew in milliseconds; positive = future, negative = past |
| `id`      | `String` | `""`    | Container filter; empty = all JVM-agent containers             |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosClockSkewInProcess(skewMs = 500)
void myTest() { ...}
```

---

### `@CompositeChaosFailingShutdownHook`

**What it simulates:** Shutdown-hook registration hangs for `hangMs` ms and subsequent registrations are rejected,
simulating a JVM whose graceful-shutdown subsystem has seized — causing SIGKILL escalation and skipping connection/lock
cleanup.

| Attribute | Type     | Default | Description                                                   |
|-----------|----------|---------|---------------------------------------------------------------|
| `hangMs`  | `long`   | `30000` | How long the shutdown-hook registration hangs in milliseconds |
| `id`      | `String` | `""`    | Container filter; empty = all JVM-agent containers            |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosFailingShutdownHook(hangMs = 30000)
void myTest() { ...}
```

---

### `@CompositeChaosMethodExceptionInjection`

**What it simulates:** A configurable exception is thrown at the entry point of every method matching `classPattern` and
`methodNamePattern`, providing a general-purpose fault-injection escape hatch for application-level methods without
requiring the dependency to be broken.

| Attribute           | Type     | Default                 | Description                                        |
|---------------------|----------|-------------------------|----------------------------------------------------|
| `classPattern`      | `String` | `"*"`                   | Prefix matched against the binary class name       |
| `methodNamePattern` | `String` | `"*"`                   | Prefix matched against the method name             |
| `exceptionClass`    | `String` | `"java.io.IOException"` | Binary class name of the exception to throw        |
| `id`                | `String` | `""`                    | Container filter; empty = all JVM-agent containers |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosMethodExceptionInjection(
        classPattern = "com.example.service",
        methodNamePattern = "charge",
        exceptionClass = "com.example.PaymentException")
void myTest() { ...}
```

---

### `@CompositeChaosMethodReturnCorruption`

**What it simulates:** The return value of every matched method is replaced with `null` (or numeric zero for primitives)
at method exit, simulating a misbehaving downstream that returns a legal but semantically wrong result — causing silent
data corruption without any exception at the call site.

| Attribute           | Type     | Default | Description                                        |
|---------------------|----------|---------|----------------------------------------------------|
| `classPattern`      | `String` | `"*"`   | Prefix matched against the binary class name       |
| `methodNamePattern` | `String` | `"*"`   | Prefix matched against the method name             |
| `id`                | `String` | `""`    | Container filter; empty = all JVM-agent containers |

**Example:**

```java

@AppContainer
@JvmAgentChaos
@CompositeChaosMethodReturnCorruption(
        classPattern = "com.example.inventory",
        methodNamePattern = "getQuantity")
void myTest() { ...}
```
