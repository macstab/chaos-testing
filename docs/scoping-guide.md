# Container Selection and Chaos Scoping

This guide explains how the framework decides which containers receive chaos rules, at what point in the test lifecycle
chaos is injected, and how to target individual containers when your test class starts more than one.

---

## How containers are discovered

### Plugin-based container management

`ChaosTestingExtension` does not scan the class for `GenericContainer` fields directly. Instead, it discovers containers
through the `ChaosPlugin` SPI. Each container annotation (`@RedisStandalone`, `@RedisSentinel`, custom plugins) has a
corresponding `ChaosPlugin` registered in `META-INF/services/com.macstab.chaos.core.extension.ChaosPlugin`. The
extension reads container annotations from the test class, delegates to the matching plugin to create and start the
container, and then routes chaos rules to those containers.

`ContainerResolver` (internal utility) provides field-level scanning to find `GenericContainer` instances when
field-scope chaos annotations are processed. It walks the class hierarchy and collects every field that is
assignment-compatible with `GenericContainer`.

### Marking the chaos target

The `id()` attribute on container annotations and on chaos annotations is the primary targeting mechanism.

- **`id()` is empty (default)** — the chaos rule applies to every container of the matching annotation type started for
  this test class.
- **`id()` is set** — the chaos rule applies only to the container whose annotation carries the same `id` value.

For field-level chaos annotations (annotations placed directly on a `GenericContainer` field), if `id()` is empty, the
framework derives the id from the co-located container annotation on the same field. If the container annotation also
has no id, the field name is used as the implicit id.

---

## Annotation scope: class, method, and field

### Class-level chaos (applies to all tests)

A chaos annotation placed directly on the test class is applied once at `@BeforeAll`, after all containers have been
started. It remains active for the entire test class and is removed at `@AfterAll`, before containers are stopped.

```java
@RedisStandalone
@CompositeChaosConnectionRefused         // active for every @Test in this class
class AllTestsUnderChaos {

    @Test
    void firstTest() { ... }

    @Test
    void secondTest() { ... }
}
```

### Method-level chaos (applies to one test)

A chaos annotation placed on a `@Test` method is applied at `@BeforeEach` and removed at `@AfterEach`. If a method-level
rule conflicts with an active class-level rule for the same annotation type and container, the class-level rule is *
*temporarily suspended** for the duration of that test and restored afterwards.

```java
@RedisStandalone
@CompositeChaosConnectionRefused         // active by default
class MixedChaosTest {

    @Test
    void testWithDefaultChaos() { ... }

    @Test
    @CompositeChaosThunderingHerd        // overrides the class-level rule for this test only
    void testWithOverride() { ... }
}
```

### Field-level chaos (co-located with container declaration)

A chaos annotation can be placed on the same field as a container annotation. Field-level rules have higher priority
than class-level rules. When a field rule conflicts with a class rule of the same type on the same container, the class
rule is **permanently displaced** for the lifetime of the test class — there is no restoration.

```java
@RedisStandalone(id = "cache")
@CompositeChaosConnectionRefused         // targets the "cache" container
static GenericContainer<?> cache;
```

### Priority order (highest to lowest)

1. Method-level (suspends conflicting class/field rules for the duration of the test)
2. Field-level (permanently displaces conflicting class-level rules)
3. Class-level

### Cleanup

| Scope        | Applied at    | Removed at   |
|--------------|---------------|--------------|
| Class-level  | `@BeforeAll`  | `@AfterAll`  |
| Field-level  | `@BeforeAll`  | `@AfterAll`  |
| Method-level | `@BeforeEach` | `@AfterEach` |

All rule removals happen while the container is still running, before the container is stopped.

---

## `@SyscallLevelChaos`

```java
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
class MyTest { ... }
```

This annotation declares which `libchaos-*` shared libraries must be injected into the container via `LD_PRELOAD` *
*before** `container.start()` is called.

### Why it is needed

The libchaos libraries hook kernel syscalls through `LD_PRELOAD`, which the Linux dynamic loader only honours at process
start time. The `.so` must be copied into the container and the `LD_PRELOAD` environment variable set before the
container's main process boots. `@SyscallLevelChaos` is the signal that triggers this pre-start preparation step.

Without this annotation, any attempt to use a syscall-level chaos verb (advanced connection chaos, DNS fault injection,
etc.) raises `LibchaosNotPreparedException` immediately at the call site. There is no silent fallback — the lifecycle
contract cannot be recovered once a container is running.

### Available `LibchaosLib` values

| Value                 | Module that provides the `.so` | Fault domain                                                                       |
|-----------------------|--------------------------------|------------------------------------------------------------------------------------|
| `LibchaosLib.NET`     | `macstab-chaos-network`        | TCP/UDP/Unix socket syscalls (`connect`, `bind`, `accept`, `send`, `recv`, `poll`) |
| `LibchaosLib.DNS`     | `macstab-chaos-dns`            | DNS resolver syscalls                                                              |
| `LibchaosLib.MEMORY`  | `macstab-chaos-memory`         | Memory allocator (`mmap`, `malloc` family)                                         |
| `LibchaosLib.IO`      | `macstab-chaos-disk`           | Disk I/O syscalls (`read`, `write`, `open`, `fsync`)                               |
| `LibchaosLib.TIME`    | `macstab-chaos-time`           | Clock and timer syscalls (`clock_gettime`, `gettimeofday`)                         |
| `LibchaosLib.PROCESS` | `macstab-chaos-process`        | Process and signal syscalls (`fork`, `kill`, `waitpid`)                            |

Multiple libraries can be declared simultaneously: `@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.IO})`. Each library
is prepared independently; `LD_PRELOAD` composition is handled automatically.

---

## `@ConfigureContainer`

`@ConfigureContainer` is a **field-level** annotation placed on a `GenericContainer` field. It validates that the
container was started with specific resource limits and fails the test with a clear error message if the limits are
absent or insufficient.

⚠️ This annotation is **validation-only**. Docker requires resource limits to be set at container creation time — this
annotation cannot apply limits retroactively. Set limits on the container itself using
`.withCreateContainerCmdModifier(...)`, then use `@ConfigureContainer` to assert they are present.

### Attributes

| Attribute    | Type     | Default              | Description                                                                                   |
|--------------|----------|----------------------|-----------------------------------------------------------------------------------------------|
| `memory`     | `String` | `""` (no validation) | Required memory limit, e.g. `"512M"`, `"1G"`. Fails if the container's actual limit is lower. |
| `cpus`       | `int`    | `-1` (no validation) | Required CPU count. Fails if the container's CPU count is lower.                              |
| `diskSize`   | `String` | `""` (no validation) | Required disk size, e.g. `"10G"`. Requires Linux + overlay2 driver.                           |
| `cpuShares`  | `int`    | `-1` (no validation) | Required CPU shares (relative weight). Fails if the container's shares are lower.             |
| `memorySwap` | `String` | `""` (no validation) | Required memory + swap combined limit.                                                        |

### Example

```java
@RedisStandalone
class ResourceConstrainedTest {

    @Container
    @ConfigureContainer(memory = "512M", cpus = 2)
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
        .withCreateContainerCmdModifier(cmd ->
            cmd.getHostConfig()
               .withMemory(512L * 1024 * 1024)
               .withCpuCount(2L));
}
```

For class-wide resource constraints applied at container creation time, use `@Resources(memory = "512M", cpus = "2")` on
the test class instead.

---

## Linux-host requirement

Network, DNS, memory, filesystem, and time chaos all depend on `LD_PRELOAD` syscall interception, which only works
inside Linux containers. When those scenarios detect a non-Linux Docker host (macOS Desktop / Windows WSL2), the test is
**skipped automatically** with a clear reason rather than failing.

The `@DisabledOnNonLinuxHost` condition is applied automatically by relevant annotations (such as `@RedisSentinel` and
any scenario annotation that requires native Docker networking). You do not need to add it manually for annotations
already carrying it.

If you are on macOS or Windows and need to run these tests, use a dev container or a Linux CI environment — both provide
native Docker networking.

---

## The `id()` attribute — targeting individual containers

When a test class starts more than one container, chaos rules must be scoped to avoid accidentally affecting all
containers at once.

### Multi-container example

```java
@RedisStandalone(id = "cache",   version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
class MultiContainerTest {

    @Test
    @CompositeChaosConnectionRefused(id = "cache")   // only the "cache" container is affected
    void onlyCacheIsAffected() {
        // session Redis is unaffected
    }

    @Test
    @CompositeChaosConnectionRefused                  // no id → both containers are affected
    void bothContainersAffected() { ... }
}
```

The `id()` attribute on the chaos annotation must match the `id()` attribute on the container annotation exactly. When
`id()` is empty on the chaos annotation, the rule is broadcast to all containers of the matching type.

### Field-level scoping

```java
@RedisStandalone(id = "cache")
@CompositeChaosConnectionRefused          // no id needed — co-located with container declaration
static GenericContainer<?> cacheRedis;

@RedisStandalone(id = "session")
static GenericContainer<?> sessionRedis;  // no chaos on this container
```

When the chaos annotation and the container annotation sit on the same field, the framework automatically inherits the
container's `id` value. This is the most readable way to declare per-container chaos rules.

---

## Known limitations

- **`@Nested` classes** — JUnit 5 fires a separate `beforeAll`/`afterAll` for each `@Nested` class, scoped to the nested
  class. Container annotations and chaos annotations on the outer class are not visible to nested class tests. Place
  annotations directly on the `@Nested` class if it needs its own containers.
- **Superclass annotations** — annotations declared on superclasses are not scanned. Annotate the concrete test class
  directly.
