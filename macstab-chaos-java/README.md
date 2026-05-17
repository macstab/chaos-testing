# macstab-chaos-java

Container-side wrapper for the [`chaos-testing-java-agent`](https://github.com/macstab/chaos-testing-java-agent) JVM bytecode instrumentation chaos library. Pair it with one of the framework wrapper modules to drive Spring Boot / Quarkus / Micronaut / plain-JUnit tests against a JDK 21+ container with the agent pre-installed — no Dockerfile changes required.

## Two integration models

The agent supports two attachment paths. **Both work — they cover different test shapes.**

| Path | When | Test JVM | Target JVM | What you use |
|---|---|---|---|---|
| **In-process** | Unit / slice / integration tests of your own app | == target | == test | `ChaosAgentExtension` (JUnit 5) or `@ChaosTest` (Spring) from the agent project. Self-attaches via the Attach API. |
| **Container-side** | Tests that drive a separate Java app under test via testcontainers | ≠ target | A container | `JavaAgentTransport` (this module). Copies the agent jar + sets `JAVA_TOOL_OPTIONS` on the container pre-start. |

`macstab-chaos-java` only provides the **container-side** plumbing — the in-process path is fully handled by the agent project's own test starters.

## Single-line setup per framework

Pick your framework's wrapper module. Each one transitively pulls the agent's test starter, the testkit, and this module's container-side transport.

| Framework | Gradle dependency |
|---|---|
| Plain JUnit 5 | `testImplementation(project(":macstab-chaos-java-junit5"))` |
| Spring Boot 3 | `testImplementation(project(":macstab-chaos-java-spring-boot3"))` |
| Spring Boot 4 | `testImplementation(project(":macstab-chaos-java-spring-boot4"))` |
| Micronaut | `testImplementation(project(":macstab-chaos-java-micronaut"))` |
| Quarkus | `testImplementation(project(":macstab-chaos-java-quarkus"))` |
| Bare (no framework starter) | `testImplementation(project(":macstab-chaos-java"))` |

Coordinates resolve from Maven Central as `com.macstab:macstab-chaos-java-*:<version>` once published.

---

## Quickstarts

### JUnit 5 — in-process

```java
import com.macstab.chaos.jvm.testkit.ChaosAgentExtension;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ChaosAgentExtension.class)
class HttpRetryTest {

  @Test
  void httpClientRetriesOnTransientFailure(ChaosControlPlane chaos, ChaosSession session) {
    // Delay every JDK HttpClient.send call by 500ms.
    chaos.applyPlan(
        ChaosPlan.builder()
            .scenario(ChaosScenario.builder()
                .selector(...)  // OperationType.HTTP_CLIENT_SEND
                .effect(ChaosEffect.delay(Duration.ofMillis(500)))
                .build())
            .build());

    // ... assert your client's retry / timeout / circuit-breaker behaves correctly ...
  }
}
```

### Spring Boot 3

```java
import com.macstab.chaos.jvm.spring.boot3.test.ChaosTest;
import com.macstab.chaos.jvm.api.ChaosControlPlane;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@ChaosTest                              // self-attaches the agent + injects ChaosControlPlane bean
class JdbcResilienceTest {

  @Autowired ChaosControlPlane chaos;

  @Test
  void appSurvivesJdbcConnectionAcquireFailure() {
    chaos.applyPlan(
        ChaosPlan.builder()
            .scenario(ChaosScenario.builder()
                .selector(...)   // OperationType.JDBC_CONNECTION_ACQUIRE
                .effect(ChaosEffect.rejectWith(SQLException.class))
                .build())
            .build());

    // assert your repository / service layer handles connection-pool exhaustion gracefully
  }
}
```

Spring Boot 4: identical, just swap `boot3` → `boot4` in the import and the module coordinate.

### Quarkus

The Quarkus extension wires the agent at build time. Add the dependency, then any `@QuarkusTest` automatically gets a `ChaosControlPlane` available via CDI injection.

```java
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GcPressureTest {

  @Inject ChaosControlPlane chaos;

  @Test
  void apiHandlesGcPressure() {
    chaos.applyPlan(...);  // e.g. HeapPressure stressor
  }
}
```

### Micronaut

```java
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@MicronautTest
class ThreadLeakTest {

  @Inject ChaosControlPlane chaos;

  @Test
  void threadPoolDoesNotLeakUnderChaos() {
    chaos.applyPlan(...);  // ThreadLeak stressor
  }
}
```

### Container-side — annotation-driven (recommended)

For tests that use chaos-core's container annotations (`@RedisStandalone`, `@RedisSentinel`,
etc.), add `@JvmAgentChaos` to the test class. The `ChaosTestingExtension` reflectively
detects it and calls `JavaAgentTransport.prepare()` on every container it creates, before
start. No manual calls required.

```java
@RedisStandalone(id = "app", version = "7.4")
@JvmAgentChaos                    // ← auto-prepares the agent on every annotation-created container
class MyTest {

  @Test
  void appHandlesJdbcAcquireFailure(RedisConnectionInfo info) {
    // agent is already loaded inside info.container() — push a plan or use ControlPlane
  }
}
```

`@JvmAgentChaos` mirrors `@SyscallLevelChaos(LibchaosLib.X)` for the libchaos `.so` libraries.
Both annotations can coexist on the same test class.

### Container-side — manual (any test, no annotation framework)

For tests that don't use chaos-core's annotation system, use `JavaAgentTransport` /
`CompositeJavaChaos` directly:

```java
import com.macstab.chaos.jvm.CompositeJavaChaos;
import org.testcontainers.containers.GenericContainer;

class MyContainerIntegrationTest {

  @Test
  void appBehavesUnderChaos() {
    GenericContainer<?> app =
        new GenericContainer<>("my-spring-app:latest").withExposedPorts(8080);

    CompositeJavaChaos chaos = new CompositeJavaChaos();

    chaos.prepare(app);   // copies agent jar, sets JAVA_TOOL_OPTIONS — BEFORE start
    app.start();

    String planJson = """
        { "scenarios": [ { "id":"slow-db", "selector": {...}, "effect": {...} } ] }
        """;
    chaos.applyPlan(app, planJson);

    // ... drive load against app, assert behaviour ...

    chaos.clearPlan(app);
  }
}
```

The agent's `StartupConfigPoller` notices file changes and hot-reloads plans — `applyPlan` can be called repeatedly across a single test.

---

## Requirements

- **Target container JDK: 21+.** The agent uses virtual-thread instrumentation that landed in JDK 21. Older runtimes fail at agent load.
- **Test JVM: any modern JDK** — this module targets JDK 25 like the rest of the repo.

## Known conflicts

The agent uses ByteBuddy 1.14+. **Conflicts possible with:**
- **Mockito-inline** — both rewrite final classes via ByteBuddy. Use `mockito-core` instead, or restrict mock targets.
- **JaCoCo** — limit JaCoCo's class scope to your own packages. The agent's own build restricts JaCoCo to `com.macstab.chaos.jvm.*`.
- **APM agents (Datadog, New Relic)** — load order matters. Put the chaos agent *after* the APM agent in `-javaagent` chains.

## What this module deliberately does NOT do

- Re-export `chaos-agent-api` types (`ChaosPlan`, `ChaosScenario`, `ChaosSelector`, `ChaosEffect`) — use the agent's own API; it's the canonical owner.
- Bundle the 12 MiB agent jar inside its own artifact — the jar resolves from Maven Central via the standard Gradle dependency mechanism and is discovered at runtime by classpath scan.
- Provide a JUnit 5 extension. `ChaosAgentExtension` from the agent project covers the in-process case; the container-side case is too test-shape-specific to wrap usefully — call `chaos.prepare(container)` from your existing setup hooks.

## See also

- [chaos-testing-java-agent](https://github.com/macstab/chaos-testing-java-agent) — the JVM agent itself
- `JavaAgentTransport` Javadoc — container-side delivery contract
- `CompositeJavaChaos` Javadoc — composite facade
