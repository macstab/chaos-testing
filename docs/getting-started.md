# Getting Started

## Prerequisites

| Requirement | Minimum version |
|---|---|
| JDK | 21+ |
| Docker | 20.10+ |
| JUnit | 5.x |
| Testcontainers | 1.19+ |

---

## Choosing your dependency tier

The framework is organised into three tiers. Pick the one that matches how much control you need.

### L1 — Raw syscall primitives

Individual syscall/errno injections (`@ChaosConnectEconnrefused`, `@ChaosMmapAnonEnomem`, etc.). Use when you need maximum control over exactly which syscall fails, with what errno, and at what probability. Low-level; no pre-baked scenarios.

### L2 — Named composite scenarios (single domain)

Pre-tuned combinations of L1 primitives that model a recognised failure mode in a single domain (`@CompositeChaosConnectionRefused`, `@CompositeChaosThunderingHerd`, etc.). Use when you know which failure mode you want but don't want to wire up individual syscall rules.

### L3 — Named production incidents, multi-domain (recommended starting point)

Compound scenarios that compose rules across connection, DNS, memory, time, filesystem, and JVM domains simultaneously to simulate real production incidents (`@IncidentChaosRedisFailoverStorm`, `@IncidentChaosJdbcConnectionPoolExhaustion`, etc.). Start here unless you have a specific reason to go lower.

---

## Dependency table

Add the modules you need as `testImplementation` dependencies. Each module is independent — include only what you actually use.

### L2 testpacks

#### `macstab-chaos-testpacks-connection` — Network connection chaos scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-connection:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-connection</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-dns` — DNS failure scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-dns:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-dns</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-memory` — Memory allocator failure scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-memory:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-memory</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-process` — Process and signal chaos scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-process:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-process</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-time` — Clock and timer chaos scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-time:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-time</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-filesystem` — Filesystem fault scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-filesystem:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-filesystem</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-java` — JVM chaos scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-java:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-java</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

### L3 testpacks

#### `macstab-chaos-testpacks-l3-redis` — Redis production incident scenarios

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

---

#### `macstab-chaos-testpacks-l3-jdbc` — JDBC / relational DB production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-jdbc:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-jdbc</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-http` — HTTP service production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-http:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-http</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-grpc` — gRPC production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-grpc:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-grpc</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-kafka` — Kafka production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kafka:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-kafka</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-spring-boot` — Spring Boot production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-spring-boot:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-spring-boot</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-jvm` — JVM production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-jvm:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-jvm</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-kubernetes` — Kubernetes production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-kubernetes:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-kubernetes</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-cache` — Cache production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-cache:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-cache</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-spring` — Spring Framework production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-spring:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-spring</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-feign` — Feign client production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-feign:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-feign</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

#### `macstab-chaos-testpacks-l3-system` — System-level production incident scenarios

**Gradle:**
```groovy
testImplementation 'com.macstab.chaos:macstab-chaos-testpacks-l3-system:1.0.0'
```
**Maven:**
```xml
<dependency>
  <groupId>com.macstab.chaos</groupId>
  <artifactId>macstab-chaos-testpacks-l3-system</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

---

### Redis container support module

#### `macstab-chaos-redis` — `@RedisStandalone` / `@RedisSentinel` container provisioning

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

---

## Your first L3 test

The example below uses the JVM carrier-pinning incident scenario from `macstab-chaos-testpacks-l3-jvm`. Swap the L3 annotation for any other incident annotation that matches what you want to test.

```java
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET})
class MyServiceChaosTest {

    @Container
    @AppContainer
    static GenericContainer<?> app = new GenericContainer<>("my-app:latest")
        .withExposedPorts(8080);

    @Test
    @IncidentChaosJvmCarrierPinning
    void service_survivesCarrierPinning() {
        // your assertions here
    }
}
```

### What each annotation does

| Annotation | Purpose |
|---|---|
| `@Testcontainers` | JUnit 5 Testcontainers lifecycle — starts and stops `@Container` fields automatically. |
| `@ExtendWith(ChaosTestingExtension.class)` | Activates the chaos framework lifecycle (applies/removes chaos rules around each test). |
| `@SyscallLevelChaos({LibchaosLib.NET})` | Declares that the `libchaos-net` library must be injected via `LD_PRELOAD` before container start. Required for any scenario that uses network syscall interception. |
| `@Container` | Marks the field as a Testcontainers-managed container (started once per class for `static` fields). |
| `@AppContainer` | Marks this container as the chaos target. The framework routes chaos rules to containers carrying this marker. |
| `@IncidentChaosJvmCarrierPinning` | Method-level L3 incident annotation — applies the carrier-pinning scenario for this test only, then removes it after the test completes. |

---

## Version update note

> When a new version is released, update the version string in all dependency blocks above. The current version is always in `gradle.properties` at the repository root (`version = x.y.z`).
