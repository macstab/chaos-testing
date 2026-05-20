# macstab-chaos-testing — Roadmap to 1.0.0

> *Chaos testing for the rest of us. Five annotations. No chaos expertise required.*

---

## 1. Vision & positioning

### Two products, two markets — never confuse them

`macstab-chaos-testing` is one of two related products. Each targets a different audience and ships independently.

| | **chaos-testing-java-agent** | **macstab-chaos-testing** |
|---|---|---|
| **State** | Shipped (Maven Central, `com.macstab.chaos.jvm:*:1.0.0`) | In development — 1.0.0 target |
| **Buyer** | Platform team / SRE / chaos engineer | Every Java developer who ships a service |
| **Mental model** | "I run a chaos engineering program" | "I added a Postgres dependency; my CI should verify it's resilient" |
| **Deployment** | Kubernetes ConfigMap + agent volume + env var + Grafana dashboards | `testImplementation(...)` + annotations on a test class |
| **Lifecycle moment** | Game days, scheduled experiments, production-style chaos | PR CI, every build, like unit tests |
| **Failure surface** | One layer (JVM bytecode), expertly composed | All layers (syscall + kernel + JVM + proxy + app), sensible defaults, no expertise required |
| **TAM** | Thousands of orgs with mature SRE practice | Millions of Java services worldwide |

### The category-defining claim

> *macstab-chaos-testing combines syscall-level, kernel-level, application-level, and JVM-bytecode-level chaos under one annotation-driven API. Five annotations on a test class verify that your service survives database disconnects, network partitions, GC pressure, clock skew, DNS failures, and resource exhaustion — without you ever writing a chaos rule by hand.*

There is no OSS framework in market today that occupies this position. Chaos Mesh / Litmus / Gremlin / Steadybit target the expert market. Chaos Monkey for Spring Boot is application-layer only. The combining-API + default-CI-hygiene framing is **wide open**.

### The 1.0.0 sentence test

If a Java developer who has never heard of chaos engineering reads the 1.0.0 README, they must walk away saying:

> *"Oh — I just add these annotations and my CI verifies my service is resilient. That makes sense. I can do that next sprint."*

If that sentence doesn't form in their head within 30 seconds of landing on the README, 1.0.0 is not ready.

---

## 2. Current state — what's already done

The foundation is complete. The 1.0.0 work is productisation, not engineering.

### Architecture (complete)

| Layer | Modules | Status |
|---|---|---|
| **System-level (LD_PRELOAD `.so` libraries)** | `chaos-connection`, `chaos-filesystem`, `chaos-dns`, `chaos-memory`, `chaos-process`, `chaos-time` | ✅ Six libchaos modules wired; audit-driven gap fixes applied; full C-side surface exposed; integration tests parameterised across `debian:bookworm-slim` + `alpine:3.20` |
| **Network / kernel** | `chaos-network` (tc/netem + iptables) | ✅ Single backend, well-defined surface |
| **Resource** | `chaos-cpu` (cgroups), `chaos-disk` (stress-ng + cgroups blkio) | ✅ Single backend each |
| **Proxy infrastructure** | `chaos-proxy`, `chaos-toxi-core` | ✅ Toxiproxy primitives consumed by `chaos-connection` and `chaos-cache` |
| **Application** | `chaos-cache` (Redis cache invalidation), `chaos-redis` (Sentinel topology, `enableNetworkChaos`, `enableConnectionChaos`, `ControlFacade` with `.network()`/`.connection()`) | ✅ Redis-specific orchestration |
| **JVM bytecode** | `chaos-java` (container-side `JavaAgentTransport`, `@JvmAgentChaos`); `chaos-java-junit5`, `-spring-boot3`, `-spring-boot3-test`, `-spring-boot4`, `-spring-boot4-test`, `-micronaut`, `-quarkus` framework wrappers | ✅ Container-side delivery; annotation-driven; framework wrappers split into production vs test |
| **Patterns** | `chaos-patterns` (Ramp/Wave/Noise/Burst, `then`/`repeat` composition, `RuleSwapper`, `FailurePolicy`, scheduled-callback executor) | ✅ Pattern-driven dynamic chaos |
| **Test orchestration** | `chaos-core` (`ChaosTestingExtension`, `@SyscallLevelChaos`, `@JvmAgentChaos`, plugin SPI) | ✅ Annotation-driven container preparation; reflective wiring across modules; zero compile-time cross-deps |

### Architectural patterns (consistent across libchaos modules)

- `Composite<X>Chaos` facade → `.advanced()` accessor → typed-verb interface (`Advanced<X>Chaos`)
- Sealed `<X>Effect` ADT + `<X>Selector` enum with errno compatibility matrix + `<X>Rule` record
- Annotation-driven container preparation via `@SyscallLevelChaos(LibchaosLib.X)` and `@JvmAgentChaos`
- Reflective bridge from `chaos-core` to `chaos-java` (annotation-by-FQN lookup, no compile-time dep)

### What is NOT done — the gap to 1.0.0

- ❌ Three-tier annotation surface (L1 primitives, L2 per-module scenarios, L3 cross-module incident classes — see §4 / §5)
- ❌ Gradle plugin (`chaosCheck` task)
- ❌ Central root `README.md` with the new positioning narrative
- ❌ Failure-report pretty-printer
- ❌ Plain-team quickstart documentation
- ❌ Naming alignment across libchaos modules (`Effect`/`Errno` inconsistency between dns/filesystem/connection vs memory/process/time)
- ❌ Architectural decisions locked in: per-primitive environment self-decision, composition semantics, health-check convention

---

## 3. 1.0.0 release gates

`1.0.0` ships **only when all of these are true**:

| # | Gate | Why it's required |
|---|---|---|
| 1 | The 30-second README test passes (§1) | Without this, the positioning fails on first impression |
| 2 | Every chaos module ships its full `@Chaos<X>` L1 primitive set — one annotation per typed verb on every `Advanced<Module>Chaos` interface, plus the full JVM-agent OperationType surface. Expected cardinality: 200–350 annotations. | The foundation tier: every chaos kind in the framework reachable from one annotation on a class or method |
| 3 | Every L1 primitive is covered by at least one L2 `@CompositeChaos<X>` scenario with structured Javadoc (description / example / severity / industry reference). Expected cardinality: 100–160 scenarios across all per-module testpack modules. | L2 is where industry vocabulary enters the IDE — coverage rule guarantees no primitive is hidden from the scenario tier |
| 4 | Gradle `chaosCheck` task wired and documented | Without it, chaos tests stay opt-in special infrastructure |
| 5 | Naming alignment pass complete across libchaos modules | "One API" claim is hollow if surface uses six naming conventions |
| 6 | Failure reports show chaos context, not just service stack traces | First failure must be debuggable, otherwise users abandon |
| 7 | Plain-team quickstart docs (not chaos-engineer docs) at the top of the docs site | Target audience must find onboarding aimed at them |
| 8 | Architectural decisions locked in (per-primitive environment self-decision, composition semantics, health-check convention) | These shape every annotation written — must be settled before any L1/L2/L3 code |
| 9 | Full test suite green on Linux + macOS hosts | First-impression launch demands a working build |
| 10 | Cleanup pass: delete the ~25 stale `*_STATUS_*.md` / `SESSION_*.md` files at repo root | Repo top-level reflects the launch identity, not project history |

---

## 4. Workstreams to 1.0.0

Six workstreams. Realistic single-contributor timeline: ~3 weeks.

### W1 — Naming alignment & API consistency (1–2 days)

**Goal**: every libchaos module presents the same shape so "one API" claim is honest.

**Concrete renames**:

| Module | Today | After alignment |
|---|---|---|
| chaos-connection | `Effect`, `Errno` | `ConnectionEffect`, `ConnectionErrno` |
| chaos-filesystem | `Effect`, `Errno` | `IoEffect`, `IoErrno` |
| chaos-dns | `Effect`, `EaiErrno` | `DnsEffect`, `DnsEaiErrno` (keep `eai()` factory + add `errno()` synonym) |
| chaos-memory | `MemoryEffect`, `MmapErrno` | `MemoryEffect`, `MemoryErrno` |
| chaos-process | `ProcessEffect`, `ProcessErrno` | (already aligned) |
| chaos-time | `TimeEffect`, `TimeErrno` | (already aligned) |

**Additional consistency work**:
- Every `Composite<X>Chaos` exposes `.advanced()` returning the typed-verb interface. Documented as the contract.
- Every `<X>Effect` exposes `errno(...)` and `latency(...)` factories. Module-specific factories (e.g. `failAfter` on process, `offset` on time, `eai` on dns) are additive, not replacements.
- Update `chaos-redis` `ControlFacade` — its `.network()`/`.connection()` accessors stay (domain-specific naming makes sense there) but ensure documentation explains the difference from libchaos `.advanced()`.

This is a breaking change. It must land before 1.0.0 because that is the only window where breaking changes are free.

### W2 — Three-tier annotation architecture & conventions document (1 day, no code)

**Goal**: lock in the conventions so every annotation written follows the same shape across all three tiers.

**Deliverable**: `ANNOTATION_CONVENTIONS.md` (in `docs/`) defining the items below. **No annotation code begins until this document is written and reviewed.**

#### The three tiers

| Tier | Prefix | Lives in | Purpose |
|---|---|---|---|
| **L1 — primitive** | `@Chaos<X>` | The existing chaos module that owns the chaos kind (e.g. `chaos-memory`, `chaos-process`) | One chaos kind per annotation. Configurable via annotation attributes. User can place on test class OR method. Always available — `@Chaos*` annotations and the raw API are siblings, never one-or-the-other. |
| **L2 — per-module scenario** | `@CompositeChaos<X>` | New per-module testpack module (`macstab-chaos-testpacks-<module>`) | Industry-canon named scenarios. Each scenario combines L1 primitives within a single chaos module. Coverage rule: every L1 primitive is reachable through at least one L2 scenario. |
| **L3 — cross-module incident** | `@FullChaos<X>` | New parent module (`macstab-chaos-testpacks-full`) | Cross-cutting incident classes named after industry-canon scenarios. Each L3 annotation MUST genuinely span multiple chaos modules — a scenario living in one fault domain belongs at L2, not L3. As many as useful, none for the sake of count. |

#### Naming convention — the prefix IS the tier

A reader can tell which tier any annotation belongs to without opening Javadoc:

- `Chaos*` → primitive, one chaos kind, one module
- `CompositeChaos*` → scenario, one module, multiple primitives
- `FullChaos*` → cross-cutting, multiple modules

IDE autocomplete becomes self-documenting: typing `Chaos` surfaces primitives, `Composite` surfaces per-module scenarios, `Full` surfaces cross-module incidents. Each prefix has a distinct shape mentally.

#### L1 — primitive annotation contract

- One annotation per chaos kind the module can produce
- Lives **inside** the chaos module — no separate pack-module required for primitive use
- Configurable through annotation attributes (probability, duration, errno, target endpoint, etc.) — the user never has to write the API for the common case
- The same primitive is reachable through the typed-verb API too (`AdvancedXChaos.<verb>`) for power users
- Each primitive self-decides at runtime whether it can execute in the current environment (Docker available? libchaos `.so` present? JVM agent attached?) and **skips cleanly** with a clear reason if it can't — does not fail
- Placeable on test class OR method
- Example:
  ```java
  @ChaosDiskFull(path = "/data", probability = 1.0, durationSeconds = 10)
  void writeSurvivesEnospc() { … }
  ```

#### L2 — per-module scenario annotation contract

- Industry-canon scenario names where they exist (e.g. `ThunderingHerd`, `Brownout`, `GcPause`, `NetworkPartition`, `DatabaseFailover`). If no industry term fits the scenario, invent a clear name — `<ProblemDescription>` style.
- Coverage rule: every L1 primitive in the module must be reachable through at least one L2 scenario in that module's testpack
- Lives in `macstab-chaos-testpacks-<module>` (sibling to the chaos module it consumes)
- Each module's testpack module depends ONLY on its own chaos module — no cross-pack imports
- **Every L2 annotation gets a structured Javadoc** (see template below)
- Configurable via annotation attributes; the defaults represent the standard form of the scenario
- Inapplicable scenarios (e.g. sentinel-failover when no sentinel topology exists) report **SKIPPED with reason**, not FAILED

#### L3 — cross-module incident annotation contract

- Each L3 annotation references multiple L1 primitives across multiple chaos modules, OR multiple L2 scenarios from different modules
- Named after industry-canon incident classes (`GrayFailure`, `CascadingFailure`, `RetryStorm`, `KubernetesNodeFailure`, …) — terms an SRE recognises on sight
- Lives in `macstab-chaos-testpacks-full`
- Same Javadoc structure as L2
- No minimum or maximum count — ships as many as are genuinely useful

#### Javadoc template (L2 + L3 — required structure)

Every L2 and L3 annotation's Javadoc opens with three labelled sections so a developer understands the scenario in 30 seconds without reading the chaos module docs:

```java
/**
 * <h2>What this is</h2>
 * <p>{One-paragraph description of the failure mode in plain English — what
 *    breaks in production, and what the user-visible symptom looks like.}
 *
 * <h2>How it's created</h2>
 * <p>{Concrete description of the chaos primitives this scenario fires —
 *    name the L1 annotations / typed verbs used, with the default values.
 *    Include an example "in production this happens when …" sentence.}
 *
 * <h2>How bad it is</h2>
 * <p>{Severity rating — Mild / Moderate / Severe / Critical — plus a
 *    one-paragraph production-impact class. "Service degraded but
 *    responsive", "service drops requests but recovers on its own",
 *    "service requires operator intervention", etc.}
 *
 * <h2>Industry references</h2>
 * <p>{Cite the term's origin: Netflix tech blog, Google SRE book,
 *    O'Reilly "Chaos Engineering", AWS Well-Architected, etc. — the
 *    documentation IS the credibility signal.}
 *
 * <h2>Example</h2>
 * <pre>{@code
 *   @<Annotation>
 *   class MyResilienceTest { … }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
```

L1 annotation Javadoc is lighter — single summary, attribute documentation, link to the underlying typed verb. The scenario-canon framing applies only at L2 / L3.

#### Health-check hook (cross-tier)

Primary convention: method annotation
```java
@ServiceHealthCheck
void healthy() {
    assertThat(service.ping()).isEqualTo("ok");
}
```

Secondary fallback: Spring Boot Actuator `/actuator/health` auto-detect when annotation absent and Spring context present.

Missing both → L2 / L3 annotations fail fast at test startup with a clear error pointing at the convention. Never run chaos without a health check — there is no possible pass signal. L1 primitives can run without a health check (a user using L1 directly is doing manual chaos).

#### Override mechanism (cross-tier)

- Annotation attributes for the common case
- Drop down to the raw chaos API for anything the parameter surface doesn't cover
- **No third configuration-class layer**

#### Failure report contract (cross-tier)

Every annotation produces failure output of the same shape:

```
✗ @CompositeChaosNetworkPartition.surviveSplitBrain
  Chaos applied:   tc netem 100% loss for 5s between app and redis-cache
  Failed at:       2026-05-17 14:32:11.482 (4.7s into the test)
  Health check:    MyServiceTest#healthy() — assertion failed: ping returned null
  Container state: redis-cache (running), last successful response 200ms ago
  Reproduce:       ./gradlew chaosCheck --tests surviveSplitBrain
```

#### JUnit 5 mechanism (cross-tier)

- Annotation is meta-annotated with `@ExtendWith(<TierExtension>.class)` for auto-activation
- L2 / L3 extensions implement `TestTemplateInvocationContextProvider` and generate one test per primitive the scenario fires
- L1 extension applies the chaos for the test method's lifetime and removes it afterwards
- Each generated test: apply chaos → invoke health check → remove chaos → record pass/fail

### W3 — Hero scenario catalogue (3–5 days)

**Goal**: deliver the full L1 + first L2 scenarios + first L3 scenarios as the launch story.

Three deliverables in order:

**1. L1 primitives — every chaos module ships its `@Chaos<X>` annotations.**

For each existing chaos module, audit the typed-verb surface (`AdvancedXChaos`) and ship one `@Chaos<X>` annotation per chaos kind, configurable via attributes. Examples per module are in §5.

**2. L2 scenarios — at least one per L1 primitive, industry-named where possible.**

For each module, create `macstab-chaos-testpacks-<module>` with `@CompositeChaos<X>` annotations covering every L1 primitive shipped in step 1. Industry term used when it exists; coined term when not. Full scenario catalogue in §5.

**3. L3 cross-cutting incident scenarios.**

`macstab-chaos-testpacks-full` ships `@FullChaos<X>` annotations for incident classes that span multiple modules. Curated, not exhaustive — every entry must justify its place by spanning ≥2 chaos modules and matching an industry-recognised incident class.

**Each L2 / L3 annotation must ship with the structured Javadoc** (description / example / severity / industry reference / code example — template in W2).

### W4 — Gradle plugin: `chaosCheck` task (2–3 days)

**Goal**: chaos tests run as a standard CI step, not opt-in special infrastructure.

**Module**: new `macstab-chaos-gradle-plugin`

**Behaviour**:
- Auto-activates when any `macstab-chaos-testpacks-*` module is on the test classpath
- Registers a `chaosCheck` task that runs test classes carrying any `@<Area>ResilienceTests` annotation
- Output formatted per the failure-report contract (W2)
- Wires into `check` by default; opt-out via `chaos { runOnCheck = false }`

**Target user experience**:
```bash
$ ./gradlew chaosCheck

> Task :chaosCheck
@SystemResilienceTests
  ✓ survivesMemoryPressure          (8.2s)
  ✓ survivesCpuThrottle             (15.1s)
  ✗ survivesDnsResolveFailure       (12.4s)
      Chaos: getaddrinfo → EAI_AGAIN @ 0.5
      Health check failed at t=4.2s: ping returned 503
  ✓ survivesClockSkew               (11.0s)
  ✓ survivesForkExhaustion          (9.8s)
  ✓ survivesFsyncFailure            (10.5s)

5/6 passed
```

### W5 — Failure-report pretty-printer (2–3 days)

**Goal**: when a chaos test fails, a developer understands WHY in under 30 seconds.

**Deliverable**: a `ChaosTestReporter` JUnit listener that augments standard failures with chaos context.

**Behaviour**:
- Captures chaos timeline (what was applied, when, on which container)
- Captures container state (running, last health response, recent logs)
- Generates failure-report output per the contract in W2
- Outputs to both console and an HTML report at `build/reports/chaos/`

### W6 — Documentation (1–2 days)

**Deliverable 1: Root `README.md`**

Sections:
1. Headline — "Chaos testing for the rest of us" (or whatever final tagline)
2. 30-second elevator pitch — what it is, who it's for
3. 5-line quickstart — copy-paste Gradle + annotation + run
4. Module overview table — what exists, what to add when
5. The two-product split (agent vs framework) — clear "which do I want" guidance
6. Deeper dives — links to per-module READMEs for power users

**Deliverable 2: Plain-team quickstart guides** at `docs/quickstart/`:
- `spring-boot-postgres.md` — "Your Spring Boot service uses Postgres. Here are the 2 annotations you need."
- `spring-boot-redis.md`
- `spring-boot-kafka.md`
- `quarkus-database.md`
- `micronaut-redis.md`

Each guide is concrete, copy-paste-able, under one page.

**NOT in 1.0.0 docs** (lands in 1.x): chaos engineering theory, competitor-comparison marketing, advanced patterns guide.

---

## 5. Scenario catalogue — every module, every tier

The L2 scenarios are named after industry-canon chaos-engineering terms wherever they exist. Where no industry term fits the scenario, a clear `<ProblemDescription>` name is coined. Each scenario maps to one or more L1 primitives from the owning module.

This catalogue is the **launch material**. It tells a developer exactly what scenarios they can drop on a test class without reading docs.

> **Cardinality note.** The tables below show **representative examples per module + the expected per-module count**. The actual L1 list is bounded by the audit of each `Advanced<Module>Chaos` interface — every typed verb on every interface gets a matching annotation. Expected totals: **L1 = 200–350 annotations** (dominated by the JVM agent's 75 OperationTypes), **L2 = 100–160 scenarios** (coverage rule: every L1 reachable from ≥1 L2), **L3 = 10–25** (admission rule keeps it lean).

### L1 primitives — one per chaos kind, inside each chaos module

These already exist as typed-verb APIs (`AdvancedXChaos`). Each one ships a corresponding `@Chaos<X>` annotation, configurable via attributes (errno, probability, duration, target endpoint, …).

#### ✅ L1 tier — delivered (as-shipped counts)

| Module             | Shipped L1 count | Notes |
|--------------------|------------------|-------|
| chaos-memory       | **52**           | Full matrix: 45 errno (7 selectors × valid errnos per `MemorySelector.validErrnos()`) + 7 latency (one per selector). |
| chaos-process      | **108**          | Three effect families: 50 errno + 8 latency + 50 FailAfter — libchaos-process is the only library with the FailAfter counter-gated effect. |
| chaos-time         | **29**           | 24 errno (4 selectors × 6 errnos, no per-selector restrictions) + 4 latency + 1 unique CLOCK_GETTIME-only Offset. TimeClock qualifier deferred to the imperative API. |
| chaos-dns          | **18**           | 15 EaiFault (3 wildcard selector kinds × 5 EAI errnos) + 3 latency. Per-host targeting + richer effects (REWRITE/SERVICE/OVERRIDE/FILTER_FAMILY/LIMIT/SHUFFLE) deferred to the imperative API. |
| chaos-connection   | **47**           | Curated 36 errno across 8 NetOperations + 9 latency + 1 RECV-only Corrupt + 1 POLL-only Timeout. Endpoint always wildcard at L1. |
| chaos-filesystem   | **53**           | Curated 36 errno across 13 IoOperations + 13 latency + 2 WRITE/PWRITE-only Torn + 2 READ/PREAD-only Corrupt. PathPrefix always wildcard at L1. |
| chaos-java (JVM)   | **141**          | Full typed surface against `chaos-agent-api 1.0.0`. 140 typed annotations across 19 selector-family sub-packages (thread / executor / queue / async / scheduling / shutdown / class_loading / monitor / jvm_runtime / nio / network / thread_local / http_client / jdbc / dns / ssl / file_io / method / stressors) + 1 escape-hatch `@ChaosJvmPlan` for hand-written plans. |
| **Total**          | **448**          | All landed in a single PR. |

**Foundation in chaos-core:** `@ChaosL1` meta-annotation + `L1Translator` interface + `L1AnnotationProcessor` (class- and method-scope walking, ERROR/ABORT routing via `OnMissingEnv`, container-reset cleanup fallback) + `ChaosApplicationReport` (per-test applied/skipped summary). `ChaosTestingExtension` grows `BeforeEachCallback` + `AfterEachCallback` to support method-scope L1 annotations alongside class-scope ones.

**Compile-time selector × errno safety.** Each L1 annotation encodes exactly one legal (selector, errno, effect) tuple — invalid POSIX combinations have no annotation class. Per-annotation `@<Module><Effect>Binding` meta-annotations expose the tuple to per-effect parameterised translators (one translator class per effect family, not per annotation), keeping the code surface tiny (~25 translator classes total) versus the file count of annotations (308 generated mechanically by per-module Python scripts).

**Tests: 113/113 green** — chaos-core (13), memory (22), process (17), time (12), dns (9), connection (15), filesystem (14), java (37 = 33 interceptor/stressor table-driven + 3 MethodSelector + 1 accumulator-rollback). Integration tests against real containers deferred to follow-on (`L1AnnotationIntegrationTest` per module is the established pattern; runs against alpine/debian for libchaos modules; chaos-java has the existing `JavaAgentTransportIntegrationTest` covering the agent file-transport path end-to-end).

**JVM L1 design specifics:**

- **Compile-time selector × operation safety:** Each annotation encodes one (selector kind, operation type) tuple via `@JvmInterceptorBinding`, plus the effect-specific attributes (delayMs / message / exceptionClassName / etc.). Invalid combinations have no annotation class.
- **MethodSelector annotations** carry `classPattern` + `methodNamePattern` string attributes (prefix-matched); at least one must be non-blank — the typed `MethodSelector` rejects the all-`ANY` combination by design to prevent JVM-wide instrumentation.
- **`JvmPlanAccumulator`** maintains per-container active-scenario state because the cross-container wire (`CompositeJavaChaos.applyPlan`) is wholesale plan replacement, not per-rule activate/deactivate. `addScenario` re-serialises the merged plan via Jackson and pushes; **rolls back** on push failure so the in-memory state always matches what the agent has been told.
- **23 parameterised translators** (8 interceptor effect families + 15 stressor effects). Per-effect rather than per-annotation because attribute shapes vary per effect kind.
- **Single dependency addition:** `jackson-databind` + `jackson-datatype-jsr310` for plan serialisation. `chaos-agent-api:1.0.0` was already on `macstab-chaos-java`'s classpath.


| Module | Expected L1 count | Representative annotations (examples, not exhaustive) |
|---|---|---|
| chaos-memory | ~12 | `@ChaosMmapEnomem`, `@ChaosMmapAnonEacces`, `@ChaosMmapFileEbadf`, `@ChaosMprotectFail`, `@ChaosMadviseFail`, `@ChaosMunmapFail`, `@ChaosMemoryLatency` |
| chaos-cpu | ~3 | `@ChaosCpuThrottle`, `@ChaosCpuPin`, `@ChaosCpuStarve` |
| chaos-disk + chaos-filesystem | ~40 | `@ChaosOpenEnospc`, `@ChaosReadEio`, `@ChaosWriteEnospc`, `@ChaosFsyncEio`, `@ChaosTornWrite`, `@ChaosCorruptRead`, `@ChaosUnlinkEacces`, `@ChaosRenameFromEacces`, `@ChaosRenameToEexist`, `@ChaosTruncateEio`, `@ChaosAllocateEnospc`, `@ChaosIoLatency` × 13 syscalls |
| chaos-process | ~24 | `@ChaosForkEagain`, `@ChaosForkEnomem`, `@ChaosPthreadCreateEagain`, `@ChaosPthreadCreateEbusy`, `@ChaosExecveEacces`, `@ChaosExecveatEnoent`, `@ChaosWaitpidEintr`, `@ChaosWaitpidEsrch`, `@ChaosForkFailAfter`, `@ChaosPthreadCreateFailAfter`, `@ChaosProcessLatency` × 7 syscalls |
| chaos-time | ~9 | `@ChaosClockGettimeEinval`, `@ChaosClockGettimeOffset`, `@ChaosClockGettimePerClock`, `@ChaosNanosleepEintr`, `@ChaosNanosleepLatency`, `@ChaosUsleepEintr`, `@ChaosUsleepLatency`, `@ChaosClockSkew`, `@ChaosClockFreeze` |
| chaos-dns | ~16 | `@ChaosDnsEaiAgain`, `@ChaosDnsEaiFail`, `@ChaosDnsEaiNoname`, `@ChaosDnsEaiMemory`, `@ChaosDnsEaiSystem`, `@ChaosDnsLatency`, `@ChaosDnsRewrite`, `@ChaosDnsService`, `@ChaosDnsOverride`, `@ChaosDnsFilterFamily`, `@ChaosDnsLimit`, `@ChaosDnsShuffle`, `@ChaosDnsBlackhole`, `@ChaosReverseDnsFail`, `@ChaosReverseDnsRewrite`, `@ChaosReverseDnsLatency` |
| chaos-network | ~6 | `@ChaosNetLatency`, `@ChaosNetJitter`, `@ChaosNetPacketLoss`, `@ChaosNetBandwidthLimit`, `@ChaosNetPartition`, `@ChaosNetBlackhole` |
| chaos-connection | ~30 | `@ChaosConnectRefused`, `@ChaosConnectTimeout`, `@ChaosConnectEhostunreach`, `@ChaosConnectEnetunreach`, `@ChaosBindEaddrinuse`, `@ChaosBindEaddrnotavail`, `@ChaosAcceptFail`, `@ChaosSendEpipe`, `@ChaosSendEnobufs`, `@ChaosRecvEcorrupt`, `@ChaosRecvEintr`, `@ChaosPollTimeout`, `@ChaosSocketEafnosupport`, `@ChaosListenFail`, `@ChaosShutdownEnotconn`, `@ChaosConnectionLatency` × ~5 ops |
| chaos-redis | ~5 | `@ChaosRedisSentinelFailover`, `@ChaosRedisEviction`, `@ChaosRedisReplicationLag`, `@ChaosRedisPersistencePause`, `@ChaosRedisRoleSwap` |
| **chaos-java** (JVM agent) | **80–150** | One annotation per (OperationType × applicable effect) combination across the agent's 75 OperationTypes and 9 interceptor effects + 15 stressors. Examples: `@ChaosJdbcAcquireDelay`, `@ChaosJdbcAcquireReject`, `@ChaosJdbcCommitDelay`, `@ChaosJdbcRollbackInjectException`, `@ChaosHttpClientDelay`, `@ChaosHttpClientReject`, `@ChaosHttpClientCorruptResponse`, `@ChaosGcPressure`, `@ChaosThreadLeak`, `@ChaosDeadlock`, `@ChaosMonitorContention`, `@ChaosVirtualThreadPinning`, `@ChaosSafepointStorm`, `@ChaosMethodDelay`, `@ChaosMethodInjectException`, `@ChaosMethodCorruptReturn`, `@ChaosClassLoadFail`, `@ChaosJndiLookupFail`, `@ChaosObjectStreamFail`, `@ChaosInvokeReject`, `@ChaosBlockingQueueOverflow`, `@ChaosScheduledTaskMissed`, `@ChaosShutdownHookHang`, `@ChaosClockSkew`, `@ChaosMetaspacePressure`, `@ChaosDirectBufferPressure`, `@ChaosCodeCachePressure`, `@ChaosStringInternPressure`, `@ChaosFinalizerBacklog`, `@ChaosReferenceQueueFlood`, `@ChaosSpuriousWakeup`, `@ChaosKeepAlive`, … |

**Realistic L1 total: 200–350 annotations.** The JVM agent dominates the count; the libchaos modules contribute ~120 between them.

> The full L1 list per module is generated during W3 phase 1 by walking the `Advanced<Module>Chaos` interface and emitting one `@Chaos<X>` annotation per typed verb. Mechanical work, bounded by what already exists.

### L2 scenarios — industry-canon names per module

Each scenario name links a real production-incident class (with its industry source where it has one) to the L1 primitives that simulate it. Each annotation ships with the W2 Javadoc template (description / example / severity / industry reference).

The tables below are **representative — final per-module count in the section header**. Each module's testpack is generated such that every L1 primitive in the module is reachable from at least one L2 scenario.

**chaos-memory → `macstab-chaos-testpacks-memory`** — expected ~8 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosBrownout` | Netflix term — partial capacity loss, degraded but responsive | `@ChaosMmapEnomem` (low probability) + memory cgroup squeeze |
| `@CompositeChaosMemoryLeak` | Universal term | `@ChaosMmapEnomem` (gradual ramp via chaos-patterns) |
| `@CompositeChaosColdStart` | Universal term | Unwarmed allocators + jvm warmup amplification |
| `@CompositeChaosOomKill` | Linux kernel term | `@ChaosMmapEnomem` (1.0 probability) + cgroup memory.max hit |
| `@CompositeChaosThreadStackExhaustion` | Coined | `@ChaosMmapAnonEagain` (the path pthread_create uses for stacks) |
| `@CompositeChaosDlopenFailure` | Coined | `@ChaosMmapFileEbadf` (mmap of the library fd) |
| `@CompositeChaosJitCompilationFailure` | Industry term | `@ChaosMprotectEacces` (PROT_EXEC denial) |
| `@CompositeChaosMemoryReclaimFailure` | Coined | `@ChaosMadviseEnomem` (MADV_DONTNEED / MADV_FREE failure) |

**chaos-cpu → `macstab-chaos-testpacks-cpu`** — expected ~4 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosNoisyNeighbour` | Cloud-computing term | Sustained `@ChaosCpuThrottle` to 30% |
| `@CompositeChaosCpuStarvation` | Universal | Sustained `@ChaosCpuThrottle` to 10% under load |
| `@CompositeChaosCpuQuotaSpike` | Coined | Brief `@ChaosCpuThrottle` to 5% during load test |
| `@CompositeChaosNumaPinningRegression` | Coined | `@ChaosCpuPin` to a single core |

**chaos-disk + chaos-filesystem → `macstab-chaos-testpacks-disk` / `-filesystem`** — expected ~15 scenarios combined

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosDiskFull` | Universal — `ENOSPC` incident | `@ChaosDiskFull` |
| `@CompositeChaosSlowDisk` | Universal | `@ChaosDiskSlow` (sustained high latency) |
| `@CompositeChaosFsyncStorm` | Coined (no industry term) — fsync becomes the bottleneck under load | `@ChaosFsyncFail` (intermittent) + `@ChaosDiskSlow` |
| `@CompositeChaosTornWrite` | Database-engineer term | `@ChaosTornWrite` on writes to the target path |
| `@CompositeChaosReadCorruption` | Universal | `@ChaosCorruptRead` 0.1 probability |
| `@CompositeChaosReadOnlyFs` | Universal | `@ChaosWriteEacces`, `@ChaosUnlinkEacces`, `@ChaosRenameEacces` |
| `@CompositeChaosFdExhaustion` | Universal | `@ChaosOpenEmfile` after N successful opens |
| `@CompositeChaosRenameRace` | Coined | `@ChaosRenameFromEnoent` + `@ChaosRenameToEexist` |
| `@CompositeChaosUnlinkRace` | Coined | `@ChaosUnlinkEnoent` |
| `@CompositeChaosTruncateFailure` | Coined | `@ChaosTruncateEio` |
| `@CompositeChaosSlowDirectoryScan` | Coined | `@ChaosReadLatency` on directory reads |
| `@CompositeChaosWalFsyncDelay` | Database-engineer term — WAL flush bottleneck | `@ChaosFsyncLatency` (sustained, slow) |
| `@CompositeChaosBlockDevicePressure` | Cloud-failure term | Combination of slow IO + ENOSPC + EIO probabilistic mix |
| `@CompositeChaosDataCorruptionSilent` | Industry term | `@ChaosCorruptRead` low probability sustained |

**chaos-process → `macstab-chaos-testpacks-process`** — expected ~12 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosPodKill` | Kubernetes term | `@ChaosKillProcess` (SIGKILL, no cleanup) |
| `@CompositeChaosGracefulShutdown` | Universal | `@ChaosKillProcess` (SIGTERM + drain window) |
| `@CompositeChaosThreadPoolExhaustion` | Universal | `@ChaosPthreadCreateFail` after N successful creations |
| `@CompositeChaosForkBomb` | Unix term | `@ChaosForkFail` after fork-rate spike |
| `@CompositeChaosHardKill` | Universal | `@ChaosKillProcess` (SIGKILL, no drain) |
| `@CompositeChaosExecveFailure` | Coined | `@ChaosExecveEacces` (noexec mount) + `@ChaosExecveEnomem` |
| `@CompositeChaosSpawnFailure` | Coined | `@ChaosPosixSpawnEnomem`, `@ChaosPosixSpawnpEnoent` |
| `@CompositeChaosZombieAccumulation` | Universal | `@ChaosWaitpidEchild` causing waitpid to fail to reap |
| `@CompositeChaosOrphanedProcess` | Universal | `@ChaosWaitpidEsrch` |
| `@CompositeChaosSignalInterruptedSyscall` | Universal | `@ChaosWaitpidEintr` |
| `@CompositeChaosProcessLimitHit` | Universal | `@ChaosForkEagain` (RLIMIT_NPROC) |
| `@CompositeChaosExecvePermissionDenied` | Universal | `@ChaosExecveEacces` |

**chaos-time → `macstab-chaos-testpacks-time`** — expected ~7 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosClockSkew` | Distributed-systems term | `@ChaosClockSkew` ±500ms on CLOCK_REALTIME |
| `@CompositeChaosLeapSecond` | Linux historical incident class | `@ChaosClockOffset` simulating a +1s discontinuity |
| `@CompositeChaosSlowMonotonic` | Coined — monotonic clock drift | `@ChaosClockSkew` on CLOCK_MONOTONIC (rare and surprising bug class) |
| `@CompositeChaosFrozenClock` | Coined | `@ChaosClockFreeze` on CLOCK_REALTIME |
| `@CompositeChaosTimeTravel` | Coined — clock jumps backwards | `@ChaosClockGettimeOffset` with negative delta |
| `@CompositeChaosNanosleepInterruption` | Universal | `@ChaosNanosleepEintr` |
| `@CompositeChaosTimerCascade` | Coined | `@ChaosNanosleepLatency` + `@ChaosUsleepLatency` together |

**chaos-dns → `macstab-chaos-testpacks-dns`** — expected ~9 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosDnsFailure` | Universal | `@ChaosDnsEaiFail` on the target hostname |
| `@CompositeChaosDnsTimeout` | Universal | `@ChaosDnsLatency` 5–10s on the target hostname |
| `@CompositeChaosDnsCachePoisoning` | Security term | `@ChaosDnsRewrite` to a fixed wrong answer |
| `@CompositeChaosDnsBlackhole` | Universal | `@ChaosDnsBlackhole` on the target hostname |
| `@CompositeChaosDnsTemporaryFailure` | Universal | `@ChaosDnsEaiAgain` (transient, retryable) |
| `@CompositeChaosDnsServiceRedirection` | Coined | `@ChaosDnsService` to a wrong service token |
| `@CompositeChaosIpv6OnlyResolution` | Cloud-failure term | `@ChaosDnsFilterFamily` to inet6 only |
| `@CompositeChaosShuffledAnswerOrder` | Coined | `@ChaosDnsShuffle` (load-balancer regression test) |
| `@CompositeChaosReverseDnsFailure` | Universal | `@ChaosReverseDnsFail` for logging / audit-trail tests |

**chaos-network → `macstab-chaos-testpacks-network`** — expected ~8 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosNetworkPartition` | Distributed-systems term | `@ChaosNetPartition` between app and target |
| `@CompositeChaosPacketLossStorm` | Coined — high recoverable loss | `@ChaosNetPacketLoss` 30% for 30s |
| `@CompositeChaosLatencySpike` | Universal | `@ChaosNetLatency` step from 0 to 500ms |
| `@CompositeChaosBandwidthThrottle` | Universal | `@ChaosNetBandwidthLimit` to a fraction of normal |
| `@CompositeChaosAsymmetricNetwork` | Cloud-failure term | `@ChaosNetLatency` only on one direction |
| `@CompositeChaosJitterStorm` | Coined | `@ChaosNetJitter` with wide variance |
| `@CompositeChaosNetworkBlackhole` | Universal | `@ChaosNetBlackhole` (drop, no RST) |
| `@CompositeChaosFlappyNetwork` | Universal | Alternating partition + restore via chaos-patterns |

**chaos-connection → `macstab-chaos-testpacks-connection`** — expected ~12 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosThunderingHerd` | Computer-science term | `@ChaosConnectRefused` (1.0) followed by lift → many simultaneous reconnects |
| `@CompositeChaosConnectionRefused` | Universal | `@ChaosConnectRefused` 1.0 to the target |
| `@CompositeChaosSlowDownstream` | Universal | `@ChaosSendEpipe` (low probability) + `@ChaosConnectTimeout` |
| `@CompositeChaosTcpResetStorm` | Universal | `@ChaosRecvCorrupt` causing connection close |
| `@CompositeChaosConnectionLeak` | Universal | `@ChaosAcceptFail` causing servers to not close |
| `@CompositeChaosSocketEphemeralExhaustion` | Industry term | `@ChaosBindEaddrnotavail` (port range exhausted) |
| `@CompositeChaosPortAlreadyInUse` | Universal | `@ChaosBindEaddrinuse` |
| `@CompositeChaosUnreachableHost` | Universal | `@ChaosConnectEhostunreach` |
| `@CompositeChaosUnreachableNetwork` | Universal | `@ChaosConnectEnetunreach` |
| `@CompositeChaosSendBufferStarvation` | Coined | `@ChaosSendEnobufs` |
| `@CompositeChaosAcceptStorm` | Coined | `@ChaosAcceptFail` under high connection rate |
| `@CompositeChaosPollTimeout` | Universal | `@ChaosPollTimeout` |

(Filesystem scenarios merged into the chaos-disk + chaos-filesystem table above.)

**chaos-redis → `macstab-chaos-testpacks-redis`** — expected ~8 scenarios

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosRedisFailover` | Universal | `@ChaosRedisSentinelFailover` |
| `@CompositeChaosCacheStampede` | Industry term — cache miss thundering herd | `@ChaosRedisEviction` followed by load spike |
| `@CompositeChaosRedisEvictionStorm` | Coined — sustained eviction pressure | `@ChaosRedisEviction` sustained |
| `@CompositeChaosReplicationLag` | Universal | `@ChaosRedisReplicationLag` |
| `@CompositeChaosRedisPersistencePause` | Universal | `@ChaosRedisPersistencePause` (BGSAVE-induced stall) |
| `@CompositeChaosClusterRebalance` | Industry term | `@ChaosRedisRoleSwap` + `@ChaosRedisReplicationLag` |
| `@CompositeChaosRedisSlowResponse` | Universal | `@ChaosNetLatency` 200ms to redis port |
| `@CompositeChaosRedisConnectionExhaustion` | Universal | `@ChaosAcceptFail` on redis port |

**chaos-java (JVM agent integration) → `macstab-chaos-testpacks-java`** — expected ~35–50 scenarios (dominated by the agent's 75 OperationTypes × 9 interceptor effects + 15 stressors)

| L2 annotation | Industry term | Composes L1 primitives |
|---|---|---|
| `@CompositeChaosGcPause` | Universal | `@ChaosGcPressure` triggering 500ms+ pauses |
| `@CompositeChaosDeadlock` | Universal | `@ChaosDeadlock` between two managed threads |
| `@CompositeChaosConnectionPoolExhaustion` | Universal — Hikari / c3p0 max-pool incident | `@ChaosJdbcAcquireDelay` saturating the pool |
| `@CompositeChaosSlowQuery` | Universal | `@ChaosJdbcCommitDelay` for write-heavy workloads |
| `@CompositeChaosThreadStarvation` | Universal | `@ChaosPthreadCreateFail` + `@ChaosThreadLeak` |
| `@CompositeChaosVirtualThreadPinning` | JDK 21+ term | `@ChaosVirtualThreadPinning` on the carrier |
| `@CompositeChaosSafepointStorm` | JVM term | `@ChaosSafepointStorm` |
| `@CompositeChaosMonitorContention` | Universal | `@ChaosMonitorContention` |
| `@CompositeChaosClassLoadFailure` | Universal | `@ChaosClassLoadFail` |
| `@CompositeChaosJndiInjection` | Industry term (Log4Shell incident class) | `@ChaosJndiLookupFail` |
| `@CompositeChaosUnsafeDeserialization` | Industry term | `@ChaosObjectStreamFail` |
| `@CompositeChaosJdbcRollbackFailure` | Coined | `@ChaosJdbcRollbackInjectException` |
| `@CompositeChaosHttpClientCascade` | Coined | `@ChaosHttpClientDelay` triggering retry storm |
| `@CompositeChaosHttpServerError5xx` | Universal | `@ChaosHttpClientInjectException` |
| `@CompositeChaosShutdownHookHang` | Coined | `@ChaosShutdownHookHang` |
| `@CompositeChaosScheduledTaskMissed` | Coined | `@ChaosScheduledTaskMissed` |
| `@CompositeChaosBlockingQueueOverflow` | Universal | `@ChaosBlockingQueueOverflow` |
| `@CompositeChaosCompletableFutureCancellation` | Coined | `@ChaosFutureCancellation` |
| `@CompositeChaosMetaspacePressure` | JVM term | `@ChaosMetaspacePressure` |
| `@CompositeChaosDirectBufferLeak` | Universal | `@ChaosDirectBufferPressure` |
| `@CompositeChaosCodeCachePressure` | JVM term | `@ChaosCodeCachePressure` |
| `@CompositeChaosFinalizerBacklog` | JVM term | `@ChaosFinalizerBacklog` |
| `@CompositeChaosStringInternStorm` | Coined | `@ChaosStringInternPressure` |
| `@CompositeChaosNativeLibraryLoadFailure` | Coined | `@ChaosNativeLibraryLoad` |
| `@CompositeChaosJmxInvocationStorm` | Coined | `@ChaosJmxInvoke` injection |
| `@CompositeChaosZipBomb` | Security term | `@ChaosInflaterPressure` |
| `@CompositeChaosSslHandshakeFailure` | Universal | `@ChaosSslHandshakeFail` |
| `@CompositeChaosThreadLocalLeak` | Coined | `@ChaosThreadLocalLeak` |
| `@CompositeChaosReferenceQueueFlood` | Coined | `@ChaosReferenceQueueFlood` |
| `@CompositeChaosSpuriousWakeup` | Industry term (concurrency bug pattern) | `@ChaosSpuriousWakeup` |
| `@CompositeChaosMethodInterceptionInjection` | Coined | `@ChaosMethodInjectException` on a user-specified method |
| `@CompositeChaosMethodReturnCorruption` | Coined | `@ChaosMethodCorruptReturn` (NULL / ZERO / EMPTY) |
| `@CompositeChaosClockSkewInProcess` | Coined | `@ChaosClockSkew` (JVM agent variant — overrides `Instant.now()`) |
| `@CompositeChaosFailingShutdownHook` | Coined | `@ChaosShutdownHookHang` + `@ChaosSystemExit` reject |

Additional JVM scenarios added as the agent's `OperationType` list is walked exhaustively — final per-module count likely 35–50.

**Total L2 across all modules: 100–160 scenarios.**

### L3 cross-module incident scenarios → `macstab-chaos-testpacks-full`

Curated, not exhaustive. Each entry must span ≥2 chaos modules AND match an industry-recognised incident class. As many as useful — current target list:

| L3 annotation | Industry term | Spans modules |
|---|---|---|
| `@FullChaosGrayFailure` | AWS term — partial failure that bypasses health checks | network + java + filesystem |
| `@FullChaosCascadingFailure` | Distributed-systems term | connection + java + memory |
| `@FullChaosRetryStorm` | Industry term — client retries amplify upstream load | connection + network + java (HTTP client) |
| `@FullChaosKubernetesNodeFailure` | Universal Kubernetes incident class | process + network + filesystem |
| `@FullChaosRollingDeployment` | Universal | process + connection + redis (cache cold-start) |
| `@FullChaosRegionFailover` | Cloud-architecture term | network + dns + connection + redis |
| `@FullChaosDegradedDependency` | Industry term — downstream slow → retry amplification → memory pressure | network + connection + java |
| `@FullChaosColdStartStorm` | Coined — many cold starts simultaneously | java (GC + class load) + filesystem + memory |
| `@FullChaosNetworkBrownout` | Coined — partial network capacity across multiple paths | network + connection + dns |
| `@FullChaosDataCorruptionIncident` | Industry term | filesystem (torn write) + redis (replication lag) + java (JDBC commit delay) |
| `@FullChaosSplitBrain` | Distributed-systems term | network (partition) + redis (sentinel) + connection |

This list is the v1.0.0 launch catalogue. Names ship verbatim; additions are 1.1+.

### Modules that are NOT testpack candidates

These are infrastructure, not user-facing — no L2/L3 testpack module created for them:
- `chaos-core` (orchestration framework)
- `chaos-patterns` (composition primitives)
- `chaos-proxy`, `chaos-toxi-core` (Toxiproxy infrastructure consumed by other modules)
- `chaos-cache` (subsumed by `chaos-redis` testpacks — cache chaos always belongs to a specific cache backend)

---

## 6. The annotation surface — what a user actually writes

### One L1 annotation — minimum case, no scenario bundle

```java
@SpringBootTest
class MyTest {

  @Test
  @ChaosDiskFull(path = "/data", probability = 1.0, durationSeconds = 10)
  void writeSurvivesEnospc() {
    // body — service should remain responsive while disk is full
  }
}
```

Plain JUnit, one annotation, configurable. No health check required — the test itself asserts the behaviour.

### L2 scenario — industry-canon term, one annotation

```java
@SpringBootTest
@CompositeChaosThunderingHerd    // industry-canon term from CS literature
class MyTest {

  @Autowired MyService service;

  @ServiceHealthCheck
  void healthy() { assertThat(service.ping()).isEqualTo("ok"); }
}
```

One annotation, one scenario, full structured Javadoc explaining what a thundering herd is, how this fires it, and how bad the production version is.

### Multiple L2 scenarios — pick what applies to your service

```java
@SpringBootTest
@CompositeChaosRedisFailover         // redis pack
@CompositeChaosNetworkPartition      // network pack
@CompositeChaosGcPause               // java pack
@CompositeChaosDnsTimeout            // dns pack
class MyResilienceTest {

  @Autowired MyService service;

  @ServiceHealthCheck
  void healthy() { assertThat(service.ping()).isEqualTo("ok"); }
}
```

Each annotation comes from the per-module testpack the user explicitly depends on. No surprise dependencies.

### L3 cross-cutting incident

```java
@SpringBootTest
@FullChaosGrayFailure                // spans network + java + filesystem
class MyResilienceTest {

  @ServiceHealthCheck
  void healthy() { … }
}
```

One annotation tests the full incident class. The Javadoc cites the AWS source and describes the production failure mode.

### Mixing tiers is fine

```java
@SpringBootTest
@FullChaosCascadingFailure                            // L3 broad scenario
@CompositeChaosVirtualThreadPinning(durationSeconds = 60)  // L2 specific scenario, longer than default
@ChaosClockSkew(skewMillis = 500)                     // L1 primitive, overlaid on top
class MyTest { … }
```

User combines whichever tiers match the test's needs. No tier requires the others.

**The marketing claim**: every example above is copy-pasteable from the README and works on 1.0.0 release day.

---

## 7. Open architectural decisions — must be locked in W2

Three decisions shape every annotation written across L1/L2/L3. None can be deferred.

### D1 — Per-primitive environment self-decision (no auto-detect bundle)

Each L1 primitive annotation decides at runtime whether it can execute in the current environment:
- "Am I a libchaos primitive? Is the `.so` present and `LibchaosTransport.prepare()` was called?" → yes execute, no skip with reason
- "Am I a JVM-agent primitive? Is the agent attached?" → yes execute, no skip with reason
- "Am I a cgroups primitive? Am I running inside a container with cgroups visible?" → yes execute, no skip with reason

L2 / L3 bundles do not do environment detection themselves. They declare intent ("this scenario fires these primitives"); each constituent primitive self-decides. Inapplicable primitives report **SKIPPED with reason** in the test report, not FAILED.

**Why this is right**: the knowledge of "can I run here?" lives with the thing that has that knowledge (the primitive knows its delivery mechanism). No central env-detection logic to maintain. Adding a new primitive doesn't require touching any env-detection code. A bundle that fires partially in one environment and fully in another still produces a useful test result.

**Locked.** No alternatives.

### D2 — Annotation composition semantics

When the user puts both `@FullChaosCascadingFailure` AND `@CompositeChaosGcPause` on the same test class, what happens?

| Option | Behaviour |
|---|---|
| **Union (merge)** | Run all primitives from both, deduplicated. If both imply the same primitive, it runs once with the more-specific configuration. |
| **Override** | More-specific annotation wins. L1 overrides L2 overrides L3 for any overlapping primitive. |
| **Conflict-error** | Throw at startup. Force the user to pick one. |

**Locked recommendation**: union with override — run the union of all primitives, deduplicate, and when two tiers configure the same primitive, the more-specific (lower-numbered) tier's parameters win. Most intuitive: mixing tiers gives you "all the chaos with my specific tweaks on top".

### D3 — Health-check hook convention

Three options:

1. Method annotation `@ServiceHealthCheck`
2. Spring Boot Actuator `/actuator/health` auto-detect
3. Custom interface `HealthProbe`

**Locked recommendation**: hybrid 1+2.
- Primary: `@ServiceHealthCheck` method annotation
- Secondary: Spring Boot Actuator `/actuator/health` auto-detect when the annotation is absent and Spring context is present
- Tertiary error: *"no health check found — declare `@ServiceHealthCheck` on a method or enable Spring Boot Actuator"*

L1 primitives used directly on a test method don't need a health check (the test body does its own assertions). L2 / L3 require one.

---

## 8. Style guide — cross-cutting conventions

Locked in W2; applied throughout 1.0.0 work and forward.

### Three-tier annotation prefix convention

| Tier | Prefix | Where it lives | Example |
|---|---|---|---|
| L1 | `Chaos<X>` | The chaos module that ships the primitive | `@ChaosDiskFull`, `@ChaosGcPressure`, `@ChaosClockSkew` |
| L2 | `CompositeChaos<X>` | `macstab-chaos-testpacks-<module>` (per-module testpack) | `@CompositeChaosThunderingHerd`, `@CompositeChaosBrownout` |
| L3 | `FullChaos<X>` | `macstab-chaos-testpacks-full` | `@FullChaosGrayFailure`, `@FullChaosCascadingFailure` |

The prefix IS the tier. No reading of Javadoc required to know which tier any annotation belongs to. IDE autocomplete is self-documenting.

### L1 annotation Javadoc template (lighter — primitives need less context)

```java
/**
 * {One-line summary: what this primitive does and on which chaos module.}
 *
 * <p>{Concrete description of the chaos kind, link to the underlying typed
 *    verb for power users.}
 *
 * <h2>Environment requirements</h2>
 * <p>{What needs to be present for this primitive to execute. E.g.
 *    "Requires libchaos-disk preparation via @SyscallLevelChaos(LibchaosLib.IO)
 *    or skips with reason 'libchaos-disk not active'."}
 *
 * <h2>Example</h2>
 * <pre>{@code
 *   @<Annotation>(attribute = value)
 *   void myTest() { … }
 * }</pre>
 *
 * @see com.macstab.chaos.<module>.api.Advanced<Module>Chaos
 * @author Christian Schnapka - Macstab GmbH
 */
```

### L2 / L3 annotation Javadoc template (structured — scenario canon)

```java
/**
 * <h2>What this is</h2>
 * <p>{One-paragraph description of the failure mode in plain English —
 *    what breaks in production, what the user-visible symptom looks like.}
 *
 * <h2>How it's created</h2>
 * <p>{Concrete description of the chaos primitives this scenario fires —
 *    list the L1 annotations / typed verbs used, with default values.
 *    Include a "in production this happens when …" sentence.}
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>{Mild | Moderate | Severe | Critical}</strong>
 *    <br>{One-paragraph production-impact class: "service degraded but
 *    responsive", "service drops requests but recovers on its own",
 *    "service requires operator intervention", etc.}
 *
 * <h2>Industry references</h2>
 * <p>{Where the term comes from: Netflix tech blog, Google SRE book,
 *    O'Reilly "Chaos Engineering", AWS Well-Architected, etc. — the
 *    citation IS the credibility signal.}
 *
 * <h2>Example</h2>
 * <pre>{@code
 *   @<Annotation>
 *   class MyResilienceTest { … }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
```

### Error message convention

Every validation error message must:
1. State what is wrong (concrete)
2. State what is accepted (concrete list)
3. Suggest the closest valid alternative when possible
4. Include a `{@link}` to the relevant API for further reading

Example:
> *"EAGAIN is not valid for selector waitpid. Accepted errnos: [ECHILD, EINTR, EINVAL, ESRCH]. For EAGAIN, use ProcessSelector.PTHREAD_CREATE or FORK. See ProcessSelector#validErrnos for the full matrix."*

### Module + type naming conventions (post-W1 alignment)

| Concept | Pattern |
|---|---|
| Primitive chaos module | `macstab-chaos-<area>` |
| Per-module testpack module (L2) | `macstab-chaos-testpacks-<area>` |
| Cross-module testpack module (L3) | `macstab-chaos-testpacks-full` |
| Framework-wrapper module | `macstab-chaos-java-<framework>` (e.g. `-spring-boot3`, `-spring-boot3-test`) |
| Effect sealed interface | `<Area>Effect` |
| Errno enum | `<Area>Errno` (except `DnsEaiErrno` for EAI-specific codes; standard `errno()` factory still provided as a synonym for cross-module muscle memory) |
| Selector enum | `<Area>Selector` |
| Rule record | `<Area>Rule` |
| Composite facade | `Composite<Area>Chaos` |
| Typed-verb interface | `Advanced<Area>Chaos` |
| LD_PRELOAD strategy | `Libchaos<Area>Chaos` |
| L1 primitive annotation | `@Chaos<X>` |
| L2 per-module scenario annotation | `@CompositeChaos<X>` |
| L3 cross-module incident annotation | `@FullChaos<X>` |
| Health-check method annotation | `@ServiceHealthCheck` |

---

## 9. Success criteria — how we know 1.0.0 is ready

### Hard gates
- [ ] All 10 release gates from §3 pass
- [ ] `./gradlew build` green on Linux + macOS hosts
- [ ] Three TestPacks ship: system + redis + database
- [ ] `chaosCheck` task documented and works on a sample project
- [ ] Root `README.md` passes the 30-second test (§1)
- [ ] At least one plain-team quickstart per supported framework (Spring Boot 3, Spring Boot 4, Quarkus, Micronaut)
- [ ] Naming alignment pass complete with no surface inconsistencies between modules

### Soft gates
- [ ] A developer who has never used chaos testing copies the README quickstart and has a working `chaosCheck` run in under 10 minutes
- [ ] A failing chaos test produces a report a developer can debug without reading the framework source code
- [ ] No L2 / L3 annotation in any testpack module lacks the structured Javadoc (description / example / severity / industry reference)
- [ ] Every L1 primitive annotation is reachable through at least one L2 scenario
- [ ] Every error message follows the §8 convention

---

## 10. Timeline

Realistic cardinality (~250 L1 + ~130 L2 + ~15 L3 + plugin + reporter + docs) means the original 5-week estimate was wrong. Honest re-estimate:

| Week | Workstream | Deliverable |
|---|---|---|
| 1 | W1 + W2 | Naming alignment + three-tier annotation conventions document locked |
| 2 | W3 phase 1 — libchaos modules | L1 primitives for chaos-memory, chaos-disk, chaos-filesystem, chaos-process, chaos-time, chaos-dns, chaos-network, chaos-connection (~120 annotations total — mechanical, audit-driven from `Advanced<X>Chaos` interfaces) |
| 3 | W3 phase 1 — JVM module | L1 primitives for chaos-java covering the agent's 75 `OperationType` × applicable effects (~100–150 annotations — biggest single-module work) |
| 4 | W3 phase 1 — remaining + W3 phase 2 start | chaos-cpu + chaos-redis L1 + start L2 scenarios for the libchaos modules (industry-canon names + structured Javadoc) |
| 5 | W3 phase 2 — libchaos L2 | L2 scenarios for memory, disk, filesystem, process, time, dns, network, connection, cpu, redis (~80 scenarios + Javadoc) |
| 6 | W3 phase 2 — chaos-java L2 | L2 scenarios for the JVM module (~35–50 scenarios + Javadoc — second-biggest chunk) |
| 7 | W3 phase 3 + W4 | L3 cross-module incident scenarios (~15) + Gradle `chaosCheck` task |
| 8 | W5 + W6 phase 1 | Failure-report pretty-printer + root README |
| 9 | W6 phase 2 | Plain-team quickstart docs (one per supported framework × top dependency types) |
| 10 | — | Bugfix, polish, final review, hard gates verified, 1.0.0 tag |

**Eight to ten weeks of focused single-contributor work.** The bulk is mechanical L1 annotation generation from existing `Advanced<X>Chaos` interfaces — bounded by what's already designed, not creative work. The L2 Javadoc structured-template content (description / example / severity / industry reference) is where real writing effort goes; budget 3–5 minutes per annotation × 130 annotations ≈ 8–10 hours of writing alone, spread across weeks 5–6.

**Parallelisation reduces the calendar.** Multiple contributors splitting the work by module run weeks 2–6 in parallel, dropping the critical path to ~5 weeks. The L1 audit per module is independent; L2 scenarios per module are independent.

Reduce uncertainty by doing W1 (naming) and W2 (conventions doc) **before any annotation code lands** — those choices shape every L1 / L2 / L3 annotation downstream and cost nothing to revisit if done first.

---

## 11. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Per-module testpack modules drag transitive dependencies users don't want | Each `macstab-chaos-testpacks-<module>` depends only on its own chaos module — never cross-pack |
| Per-primitive env detection produces silent skip-everything runs | Every test report prints the SKIP reason per primitive; CI can fail if total skipped > threshold |
| First failure is opaque, users abandon | Failure-report pretty-printer (W5) is a 1.0.0 hard gate, not nice-to-have |
| ByteBuddy conflicts with Mockito-inline / JaCoCo / APM agents | README ByteBuddy conflict section + agent's own restricted-namespace pattern; 1.x: defensive ByteBuddy isolation if issues arise |
| JDK 21 minimum locks out teams on JDK 11 / 17 | System / network / dns / process / memory / filesystem / time L1 + L2 scenarios target JDK 17; JVM-agent scenarios require 21 (no workaround — agent feature) |
| Naming alignment break upsets existing users | Done before 1.0.0 when there are no users to break; documented in CHANGELOG |
| Launch noise drowns the positioning | Lead with the three-tier annotation surface, not the architecture |
| Industry-term coverage gaps (a scenario doesn't have an industry name) | Allowed by W2 conventions — coin a clear `<ProblemDescription>` name and document it the same way industry terms are documented |
| L1 / L2 / L3 cardinality explosion | L1 count bounded by typed-verb audit; L2 coverage rule bounds it to ≥1 per primitive; L3 admission rule (must span ≥2 modules AND match an incident class) keeps it lean |
| Config-reload race in libchaos `mtime-CAS` semantics | Integration tests prove rules apply within a known-bounded window; documented in per-scenario failure reports |
| Multiple `.so` in `LD_PRELOAD` interpose each other's hooks | Already verified for current six libchaos modules — no regression risk |

---

## 12. The launch — when 1.0.0 ships

### Hero artifact
The root `README.md`. Specifically the first 100 lines. They decide whether the project lands as "yet another deep chaos toolkit" or "the framework that put chaos engineering's vocabulary in the IDE."

### Hero example
A complete, runnable, working Spring Boot test that uses **one industry-canon L2 annotation** (e.g. `@CompositeChaosThunderingHerd` or `@CompositeChaosGcPause`) plus `@ServiceHealthCheck`, and shows a real `chaosCheck` run with both passes and one failure. Copy-paste-able, no edits required.

A second example mixing tiers (`@FullChaosCascadingFailure` + per-primitive overrides) shows the depth path.

### Hero blog post (separately, after launch)
Title contender: *"We made chaos testing as easy as adding @Test"* — leads with the developer-market framing; the architecture depth is supporting evidence, not the headline.

### Hero conference talk material
Same framing. Show one annotation, show the test running, show the failure report. The architectural depth is the "and the way it works under the hood" segment — the second half of the talk, not the first.

---

## 13. Post-1.0.0 — explicit non-goals for 1.0.0

These are valuable but **not 1.0.0 blockers**. Each lands as a focused later release.

### 1.1.0 — Adoption-driven pack expansion
- HTTP client pack (likely the second-most-requested after database)
- Kafka pack
- One more pack on adoption signal

### 1.2.0 — JVM verb helpers
- `JvmAgentVerbs` helper class in `chaos-java` with ~20 typed verbs for the most common `ChaosPlan` scenarios
- Closes the verbosity asymmetry between libchaos modules and JVM agent

### 1.3.0 — Recipe book
- `docs/recipes/` with concrete patterns for common questions
- *"How do I chaos-test a service that uses three databases?"*
- *"How do I parameterise chaos scenarios across staging environments?"*
- *"How do I run chaos in nightly CI but not on every PR?"*

### 1.4.0 — Cross-layer cookbook scenarios

The "killer demos" that prove the multi-layer story:
- Postgres WAL `fsync` returns `EIO` while application JDBC commit is in flight
- Kafka broker network partition combined with disk pressure on the broker
- Spring Boot under GC pause + DNS flap simultaneously
- HTTP client retry storm under socket TIMEOUT
- Virtual thread carrier pinning during file I/O
- Deserialization gadget rejection under monitor contention

### 1.5.0 — Kubernetes integration (`macstab-chaos-k8s`)

> Brings the chaos-testing surface to K8s deployment shape, mirroring the architecture that's already proven in test-time.

**Concept**: a small, opinionated Java API that uses **fabric8** to mutate live Kubernetes Deployments / StatefulSets / DaemonSets, applying the same chaos rule types (`DiskRule`, `NetworkRule`, `JvmRule`, …) the test framework already uses. Chaos as imperative Java code in a repo, not GitOps YAML, not a custom operator.

**Why this fits the framework's identity**:
- Reuses the existing rule taxonomy verbatim — no new DSL, no parallel API
- Same engineer, same skills, same code idioms as the test-time framework
- No CRDs, no operator, no admission webhook
- "Chaos campaigns as Java" sit naturally next to "chaos tests as JUnit"
- Combines chaos + assertions + revert into one method — awkward in YAML

**Sketch**:
```java
ChaosCluster cluster = ChaosCluster.fromKubeconfig();

cluster.deployment("user-service")
    .injectDiskChaos(IoRule.write("/data").errno(IoErrno.EIO).probability(0.05))
    .injectJvmChaos(JvmRule.jdbcCommitDelay(Duration.ofSeconds(2)))
    .applyAndWaitForRollout(Duration.ofMinutes(5));

cluster.statefulSet("postgres-replicas")
    .injectNetworkChaos(NetworkRule.replicationLag(Duration.ofMillis(300)))
    .applyAndWaitForRollout();

// run smoke tests, observe SLOs

cluster.revertAll();
```

**Bidirectional design (the killer property)**:
- `ChaosCluster.deployment(...)` patches via fabric8 (k8s mode)
- `ChaosCluster.testcontainer(...)` patches via Docker (test mode — already exists in the framework)
- **Same `IoRule`, `NetworkRule`, `JvmRule` types. Same lifecycle.** Java code that runs in a JUnit test can run unchanged against a real cluster.

**Mutation strategies per chaos type**:

| Chaos type | Application mechanism | Rollout required? |
|---|---|---|
| LD_PRELOAD `.so` | init container drops file; env sets `LD_PRELOAD` | yes (process must restart) |
| JVM agent | init container drops jar; env adds `-javaagent` | yes (JVM must restart) |
| Network chaos via `tc` | exec into pod | no |
| Disk fill via `dd` | exec into pod | no |
| Chaos rule update (config reload) | patch ConfigMap; lib reloads via `mtime-CAS` | no |

API surface explicitly distinguishes `applyAndWaitForRollout()` from `applyLive()` so users never get surprised by an unexpected restart.

**Hard parts**:
1. **Rollback discipline**. Mid-campaign crashes leave the cluster in a partial state. Solution: persist a "chaos session manifest" in a ConfigMap (or local file) listing every mutation; `revert()` reads it and undoes deterministically. Idempotent — safe to re-run.
2. **JVM agent timing**. Deployment patch adds `-javaagent`; rolling restart must complete before chaos config takes effect. Build in `awaitAgentReady()` polling on the agent's management port.
3. **Privileged operations**. `NET_ADMIN`, `SYS_PTRACE`, host PID namespace are required for some chaos types. Patcher inspects target's existing `securityContext` and either refuses (default) or honours an explicit opt-in deployment annotation. Never silently elevates.
4. **Pod selection by labels**. Real teams don't target by Deployment names. API must accept `LabelSelector` everywhere a name is accepted.
5. **Auth + execution context**. Default to dry-run; require explicit `.execute()`. Document service-account RBAC bundles for CI runner usage. Support kubeconfig contexts for engineer laptops with audit-trail logging.

**Effort estimate**: ~3–4 months, one engineer.
- ~1 month — `ChaosCluster` + `ChaosTarget` (Deployment / StatefulSet / DaemonSet wrappers) + fabric8 plumbing + auth + lifecycle (`apply` / `revert`)
- ~2 months — per-chaos-type patching strategies (init container vs. env mutation vs. exec-into-pod vs. sidecar-with-shared-PID — each fault type needs the right one)
- ~3 weeks — rollback / cleanup primitives, idempotency on retry, partial failure recovery, session manifest persistence
- ~3 weeks — integration tests against `kind` / `k3s`, sample chaos campaign repo, docs

**Decision needed before committing**: does the team have the capacity to maintain a fabric8-based code path long-term? fabric8 keeps pace with k8s API churn but the module needs ongoing support across k8s minor versions. Estimate ~10% of one engineer's time for steady-state maintenance once shipped.

### 1.x ongoing — pack catalogue expansion
- gRPC, MongoDB, Elasticsearch, S3, etc.
- Driven by GitHub issues / community demand, not speculation

### Explicit non-goals (never ship as part of macstab-chaos-testing)
- **Web UI / dashboards** — different market (Chaos Mesh / Gremlin own this); would dilute the framing
- **SaaS / hosted control plane** — commercial product territory; out of scope for OSS framework
- **Production deployment orchestration** — the JVM agent + 1.5.0 K8s integration cover this; macstab-chaos-testing-core stays test-time
- **Chaos engineering theory documentation** — Gremlin / O'Reilly books own this; we ship code, not curriculum

---

## 14. Cross-layer overlap policy

The libchaos LD_PRELOAD layer and the JVM agent layer can both fire on the same logical operation (DNS, Socket I/O, File I/O, Clock). Without coordination, double-injection produces incoherent failure modes (e.g. JVM agent throws `ChaosJdbcSuppressException` while libc returns errno → JDBC driver throws `SQLException` from a different code path).

### `ChaosLayer` enum for explicit overlap zones

```java
enum ChaosLayer { AT_JDK, AT_SYSCALL, BOTH }
```

For overlap zones, every relevant `Advanced<X>Chaos` verb that exists in both layers accepts an optional `layer` parameter. Defaults are documented per verb:

| Verb | Default layer | Rationale |
|---|---|---|
| `failDnsResolve` | `AT_SYSCALL` | libchaos-dns gives precise per-domain control with EAI errno |
| `slowDnsResolve` | `AT_SYSCALL` | same |
| `slowHttpResponse` | `AT_JDK` | JVM agent gives per-HTTP-client targeting (Apache vs OkHttp vs JDK) |
| `slowJdbcAcquire` | `AT_JDK` | JVM agent is the only layer that sees JDBC acquire |
| `connectionTimeout` | `AT_SYSCALL` | libchaos-net targets the actual socket `connect` call |

Users overriding the default explicitly opt in to the alternative layer or `BOTH` (rarely sensible).

### Test runtime event log

Every chaos application emits a structured event:
```
[chaos] T+1.2s LIBCHAOS-NET  connect → ECONNREFUSED @ 1.0 on redis-cache:6379 (rule r1)
[chaos] T+1.5s JVM-AGENT     HTTP_CLIENT_SEND delay(500ms) @ 1.0 on com.example.MyService#getRemote
[chaos] T+4.7s HEALTH-CHECK  MyServiceTest#healthy() FAILED: ping returned null
```

This event log is part of the failure-report contract (W2) and gives users a single timeline to read.

---

## 15. Where to start

**Immediate next actions** (in order):

1. **W1 — Naming alignment pass** — pure refactor, no design risk, unblocks every TestPack
2. **W2 — Write `TESTPACK_CONVENTIONS.md`** — no code, just lock the three architectural decisions and the seven conventions
3. **W3 — Build the hero `@SystemResilienceTests` pack** — proves the conventions work
4. Then W4 (plugin), W5 (reporter), W6 (docs) in parallel where possible

**Stop signal**: if any of the three architectural decisions in W2 produces team disagreement, stop and resolve before W3 begins. Locking the conventions wrong means every subsequent pack rebuilds against the new convention later.

---

## 16. Closing — why this roadmap and not another

**This roadmap is short on architecture work** because the architecture is already done. The libchaos uplift, the JVM agent integration, the Redis composition, the patterns module, the reflective extension wiring — all built. What is left is **productisation**, not engineering.

**This roadmap is opinionated about positioning** because positioning is the hard part. Three weeks of work to ship the difference between "interesting OSS release" and "category-defining launch" is a great trade.

**This roadmap explicitly defers things** because trying to ship K8s + web UI + SaaS + 8 packs + recipes all at 1.0.0 means 1.0.0 never ships, or ships diluted.

Ship the developer-market positioning cleanly. Add scope after the category lands.
